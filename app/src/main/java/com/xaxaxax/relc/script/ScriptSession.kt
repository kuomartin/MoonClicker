package com.xaxaxax.relc.script

import android.content.Context
import android.content.Intent
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.engine.ScriptEngine
import com.xaxaxax.relc.engine.ScriptRun
import com.xaxaxax.relc.engine.state.EngineRunState
import com.xaxaxax.relc.engine.state.EngineStateRepository
import com.xaxaxax.relc.core.AppSettings
import com.xaxaxax.relc.notification.ScriptStatusNotifier
import com.xaxaxax.relc.ui.displaydetail.FullscreenDisplayActivity
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.createVirtualDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 目前（或最後一次）執行的狀態。一次只會有一份腳本在跑。 */
data class ScriptSessionState(
    val script: Script? = null,
    val displayId: Int? = null,
    val runState: EngineRunState = EngineRunState.Idle,
    /** 還沒進到引擎就失敗了（Shizuku 沒連上、建不出顯示器…）。 */
    val startFailure: String? = null,
) {
    val isRunning: Boolean
        get() = runState is EngineRunState.Running || runState is EngineRunState.Starting
}

/**
 * 執行中的那一份腳本。
 *
 * 一次一個是刻意的：原生引擎是單例，而 UI 也以「執行中的那一個」來表達。要並行得連同
 * 這裡的狀態模型一起改，不是把 native 的單例拿掉就好。
 *
 * 顯示器的生命週期不屬於這裡：需要時會建，但**結束時不銷毀**——那是 Displays 頁的事。
 */
@Singleton
class ScriptSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val notifier: ScriptStatusNotifier,
    private val settings: AppSettings,
) {
    private data class RunInfo(val script: Script, val displayId: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runInfo = MutableStateFlow<RunInfo?>(null)
    private val startFailure = MutableStateFlow<String?>(null)

    val state: StateFlow<ScriptSessionState> =
        combine(runInfo, startFailure, EngineStateRepository.state) { info, failure, engine ->
            ScriptSessionState(
                script = info?.script,
                displayId = info?.displayId,
                // 引擎狀態只有在說的是同一份腳本時才採用，避免顯示上一輪的結局。
                runState = if (info != null && engine.runningScriptId == info.script.id) {
                    engine.runState
                } else {
                    EngineRunState.Idle
                },
                startFailure = failure,
            )
        }.stateIn(scope, SharingStarted.Eagerly, ScriptSessionState())

    init {
        scope.launch {
            state.collect { current ->
                if (current.isRunning && current.script != null) {
                    notifier.showRunning(current.script.name, current.displayId)
                } else {
                    notifier.cancelRunning()
                }
                // 腳本自己跑完或出錯時，原生執行緒已經結束，但取影格的 surface 還掛在
                // 顯示器上。這裡收尾，讓顯示器回到乾淨狀態（但不銷毀它）。
                if (current.script != null && current.runState.isTerminal) {
                    ScriptEngine.stop()
                }
            }
        }
    }

    /** `script.json` 說了就照它，沒說就跑實體螢幕。 */
    fun defaultTargetFor(script: Script): ScriptTarget =
        script.display?.let { ScriptTarget.NewVirtual(it) } ?: ScriptTarget.PhysicalDisplay

    fun start(script: Script, target: ScriptTarget = defaultTargetFor(script)) {
        scope.launch {
            if (state.value.isRunning) {
                startFailure.value = "Another script is already running"
                return@launch
            }
            startFailure.value = null

            val result = shizukuManager.withService { service ->
                val displayId = resolveDisplay(service, target)
                    ?: return@withService "Target display not found"

                runInfo.value = RunInfo(script, displayId)
                val started = ScriptEngine.start(
                    context = context,
                    service = service,
                    run = ScriptRun(
                        scriptId = script.id,
                        scriptDir = script.dir,
                        displayId = displayId,
                        hasVision = target.hasVision,
                    ),
                    onNotify = { title, text -> notifier.showScriptMessage(title, text) },
                    onOpenUri = ::openUri,
                )
                if (!started) return@withService "Native engine failed to start"
                if (target.hasVision && settings.autoOpenFullscreen.value) openFullscreen(displayId)
                null
            }

            startFailure.value = result.getOrElse { "Shizuku service unavailable" }
        }
    }

    fun stop() {
        scope.launch { ScriptEngine.stop() }
    }

    /**
     * 把 target 解析成實際的 displayId。
     *
     * [ScriptTarget.NewVirtual] 會先找現有同尺寸的虛擬顯示再沿用——腳本存的是「要一個
     * 長這樣的顯示器」，不是某個必然會過期的 id。
     */
    private fun resolveDisplay(service: IRelcV2Service, target: ScriptTarget): Int? =
        when (target) {
            is ScriptTarget.PhysicalDisplay -> 0
            is ScriptTarget.ExistingVirtual -> target.displayId
            is ScriptTarget.NewVirtual -> {
                val existing = runCatching { service.virtualDisplays.toList() }.getOrDefault(
                    emptyList()
                )
                existing.firstOrNull { matchesSize(service, it, target.config.width, target.config.height) }
                    ?: runCatching { service.createVirtualDisplay(target.config) }
                        .onFailure { Timber.e(it, "createVirtualDisplay failed") }
                        .getOrNull()
                        ?.takeIf { it >= 0 }
            }
        }

    private fun matchesSize(service: IRelcV2Service, displayId: Int, width: Int, height: Int): Boolean {
        val size = runCatching { service.getDisplaySurfaceSize(displayId) }.getOrNull()
        return matchesSize(size, width, height)
    }

    private fun openFullscreen(displayId: Int) {
        context.startActivity(
            Intent(context, FullscreenDisplayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("displayId", displayId)
        )
    }

    private fun openUri(uri: String) {
        try {
            val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "device.open_uri failed for $uri")
        }
    }
}

private val EngineRunState.isTerminal: Boolean
    get() = this is EngineRunState.Finished ||
            this is EngineRunState.Error ||
            this is EngineRunState.Stopped

/**
 * [ScriptSession.resolveDisplay] 沿用現有虛擬顯示時，判斷某個顯示器的建立尺寸
 * （[IRelcV2Service.getDisplaySurfaceSize]，不隨旋轉改變）符不符合請求。
 *
 * 精確比對，不接受長寬互換：接受的話，一個以 2400x1080 建立的顯示器會被拿去滿足
 * 1080x2400 的請求，腳本就跑在 surface 幾何相反的顯示器上（見 ADR-0012）。
 */
internal fun matchesSize(size: IntArray?, width: Int, height: Int): Boolean =
    size != null && size.size >= 2 && size[0] == width && size[1] == height
