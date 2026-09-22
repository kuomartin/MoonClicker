package com.xaxaxax.moonclicker.script

import android.content.Context
import android.content.Intent
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.engine.ScriptEngine
import com.xaxaxax.moonclicker.engine.ScriptRun
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.engine.state.EngineStateRepository
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.core.DisplayConfig
import com.xaxaxax.moonclicker.notification.ScriptStatusNotifier
import com.xaxaxax.moonclicker.ui.displaydetail.FullscreenDisplayActivity
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import com.xaxaxax.moonclicker.shizuku.createVirtualDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
            if (script.uniqueId == null) {
                startFailure.value = "Script is missing a uniqueId in script.json"
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
     * 給需要「真的停了才能做下一步」的呼叫端用（見 WorkbenchServer 的 `POST /run/stop`：
     * webview 收到成功回應就會立刻送下一個 start，若這裡只是 fire-and-forget，
     * `state.isRunning` 還沒翻成 false 就會被 409 擋下——實測過，ROI 調整後的
     * debounce 重啟要按兩次「開始測試」才成功）。
     *
     * `ScriptEngine.stop()` 呼叫的 `nativeStop()` 只是「請求」native 端停止，真正停止是
     * native 執行緒退出後透過 [EngineEventType.STOPPED] 事件非同步推回
     * [EngineStateRepository] 的（見該檔案），不是 `nativeStop()` 一回傳就代表停了。
     * 這裡呼叫完就等 [state] 真的翻成非 running（或逾時），呼叫端才能放心接著做下一步。
     */
    suspend fun stopAndAwait(timeoutMs: Long = 3000) {
        if (!state.value.isRunning) return
        ScriptEngine.stop()
        withTimeoutOrNull(timeoutMs) {
            state.first { !it.isRunning }
        }
    }

    /**
     * 把 target 解析成實際的 displayId。
     *
     * [ScriptTarget.NewVirtual] 會先照 [DisplayConfig.name]（腳本的 `uniqueId`）找現有虛擬顯示
     * 再沿用——腳本存的是「要一個屬於自己、長這樣的顯示器」，不是某個必然會過期的 id，
     * 也不該跟另一個剛好同尺寸的腳本共用（見 docs/plans/virtual-display-identity-by-uniqueid-plan.md）。
     * 名稱對得上但尺寸/densityDpi 不同（腳本改了 `script.json`）時，resize 既有的那個，
     * 不銷毀重建——保留 displayId，正在依附它的 consumer 才不會斷線。
     */
    internal fun resolveDisplay(service: IMoonClickerService, target: ScriptTarget): Int? =
        when (target) {
            is ScriptTarget.PhysicalDisplay -> 0
            is ScriptTarget.ExistingVirtual -> target.displayId
            is ScriptTarget.NewVirtual -> {
                val existing = runCatching { service.virtualDisplays.toList() }.getOrDefault(
                    emptyList()
                )
                val matchByName = existing.firstOrNull { id ->
                    runCatching { service.getDisplayInfo(id)?.name }.getOrNull() == target.config.name
                }
                when {
                    matchByName == null ->
                        runCatching { service.createVirtualDisplay(target.config) }
                            .onFailure { Timber.e(it, "createVirtualDisplay failed") }
                            .getOrNull()
                            ?.takeIf { it >= 0 }

                    matchesConfig(service, matchByName, target.config) -> matchByName

                    else -> runCatching {
                        service.resizeVirtualDisplay(
                            matchByName,
                            target.config.width,
                            target.config.height,
                            target.config.densityDpi,
                        )
                    }.getOrDefault(false).takeIf { it }?.let { matchByName }
                }
            }
        }

    /** 現有虛擬顯示的尺寸（rotation-invariant 的建立尺寸）與 densityDpi 是否跟腳本要求的一致。 */
    private fun matchesConfig(service: IMoonClickerService, displayId: Int, config: DisplayConfig): Boolean {
        val size = runCatching { service.getDisplaySurfaceSize(displayId) }.getOrNull()
        val densityDpi = runCatching { service.getDisplayInfo(displayId)?.densityDpi }.getOrNull()
        return matchesSize(size, config.width, config.height) && densityDpi == config.densityDpi
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
 * （[IMoonClickerService.getDisplaySurfaceSize]，不隨旋轉改變）符不符合請求。
 *
 * 精確比對，不接受長寬互換：接受的話，一個以 2400x1080 建立的顯示器會被拿去滿足
 * 1080x2400 的請求，腳本就跑在 surface 幾何相反的顯示器上（見 ADR-0012）。
 */
internal fun matchesSize(size: IntArray?, width: Int, height: Int): Boolean =
    size != null && size.size >= 2 && size[0] == width && size[1] == height
