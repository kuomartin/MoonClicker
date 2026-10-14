package com.xaxaxax.moonclicker.script

import android.content.Context
import android.content.Intent
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.engine.ScriptEngine
import com.xaxaxax.moonclicker.engine.ScriptRun
import com.xaxaxax.moonclicker.engine.ScriptStartResult
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.core.OcrManager
import com.xaxaxax.moonclicker.core.DisplayConfig
import com.xaxaxax.moonclicker.notification.ScriptStatusNotifier
import com.xaxaxax.moonclicker.ui.displaydetail.FullscreenDisplayActivity
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import com.xaxaxax.moonclicker.shizuku.createVirtualDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 一支腳本目前（或最後一次）執行的狀態。 */
data class ScriptSessionState(
    val script: Script? = null,
    val displayId: Int? = null,
    val runState: EngineRunState = EngineRunState.Idle,
    /** 還沒進到引擎就失敗了（Shizuku 沒連上、目標顯示器被佔用…）。 */
    val startFailure: String? = null,
) {
    val isRunning: Boolean
        get() = runState is EngineRunState.Running || runState is EngineRunState.Starting
}

/** 腳本 [scriptId] 以 `log(...)` 送出的一行。 */
data class ScriptLogLine(val scriptId: String, val line: String)

/** [ScriptSession.run] 的結果。 */
sealed interface ScriptStartOutcome {
    data object Started : ScriptStartOutcome

    /** 同一支腳本同時只跑一份。 */
    data object AlreadyRunning : ScriptStartOutcome

    /** 目標顯示器上已有別的腳本在跑。 */
    data class DisplayBusy(val displayId: Int, val scriptId: String) : ScriptStartOutcome

    data class Failed(val message: String) : ScriptStartOutcome
}

/**
 * 所有腳本的執行。不同顯示器上的腳本可以同時跑（每個顯示器一支，見 [ScriptEngine]）；
 * 同一支腳本同時只跑一份。每支執行中的腳本各有一則通知。
 *
 * 顯示器的生命週期不屬於這裡：需要時會建，但**結束時不銷毀**——那是 Displays 頁的事。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ScriptSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val notifier: ScriptStatusNotifier,
    private val settings: AppSettings,
    private val ocrManager: OcrManager,
) {
    private data class Run(val script: Script, val displayId: Int, val engine: ScriptEngine)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 每支腳本最後一次執行，以腳本 id 為鍵。結束後留著，畫面才看得到結局。 */
    private val runs = MutableStateFlow<Map<String, Run>>(emptyMap())
    private val startFailures = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 序列化啟動：檢查「這支腳本是否在跑」與啟動之間不能插進另一次啟動。 */
    private val startMutex = Mutex()

    /** 以腳本 id 為鍵。沒執行過、也沒啟動失敗過的腳本不在裡面。 */
    val states: StateFlow<Map<String, ScriptSessionState>> =
        combine(
            runs.flatMapLatest { map ->
                if (map.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(map.map { (id, run) -> run.engine.state.map { id to (run to it.runState) } }) { it.toMap() }
                }
            },
            startFailures,
        ) { running, failures ->
            (running.keys + failures.keys).associateWith { id ->
                val run = running[id]
                ScriptSessionState(
                    script = run?.first?.script,
                    displayId = run?.first?.displayId,
                    runState = run?.second ?: EngineRunState.Idle,
                    startFailure = failures[id],
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val isAnyRunning: StateFlow<Boolean> =
        states.map { map -> map.values.any { it.isRunning } }.stateIn(scope, SharingStarted.Eagerly, false)

    /** 執行中的腳本 id。 */
    val runningScriptIds: Flow<List<String>> =
        states.map { map -> map.filterValues { it.isRunning }.keys.toList() }.distinctUntilChanged()

    private val _logLines = MutableSharedFlow<ScriptLogLine>(extraBufferCapacity = 256)

    /** 所有腳本送出的 log，帶著腳本 id。 */
    val logLines: SharedFlow<ScriptLogLine> = _logLines.asSharedFlow()

    /** 每支腳本最後一次執行的 log 轉送。換下一次執行時取消。 */
    private val logJobs = HashMap<String, Job>()

    init {
        scope.launch {
            var shown = emptySet<String>()
            states.collect { map ->
                val running = map.filterValues { it.isRunning && it.script != null }
                running.forEach { (id, state) -> notifier.showRunning(id, state.script!!.name, state.displayId) }
                (shown - running.keys).forEach(notifier::cancelRunning)
                shown = running.keys
            }
        }

        // 執行期間持有 UserService 的租約：引擎直接拿著 service 用，不經過 withService，
        // 跑在實體螢幕上時也沒有 VD 能讓服務端知道有人在用。
        scope.launch {
            var holding = false
            isAnyRunning.collect { running ->
                if (running && !holding) shizukuManager.leases.acquire()
                if (!running && holding) shizukuManager.leases.release()
                holding = running
            }
        }
    }

    fun stateOf(scriptId: String): Flow<ScriptSessionState> =
        states.map { it[scriptId] ?: ScriptSessionState() }.distinctUntilChanged()

    // 直接問引擎：[states] 是非同步合成的，剛啟動的那一刻還沒跟上。
    fun isRunning(scriptId: String): Boolean = runs.value[scriptId]?.engine?.isRunning == true

    /** 每支執行過的腳本最後一次執行以 `data.set` 發佈的鍵值，以腳本 id 為鍵。 */
    val allSharedData: Flow<Map<String, Map<String, Any>>> =
        runs.flatMapLatest { map ->
            if (map.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(map.map { (id, run) -> run.engine.sharedData.map { id to it } }) { it.toMap() }
            }
        }

    /** [scriptId] 目前（或最後一次）執行以 `data.set` 發佈的鍵值。 */
    fun sharedData(scriptId: String): Flow<Map<String, Any>> =
        runs.map { it[scriptId]?.engine }.distinctUntilChanged()
            .flatMapLatest { it?.sharedData ?: flowOf(emptyMap()) }

    /** `script.json` 說了就照它，沒說就跑實體螢幕。 */
    fun defaultTargetFor(script: Script): ScriptTarget =
        script.display?.let { ScriptTarget.NewVirtual(it) } ?: ScriptTarget.PhysicalDisplay

    fun start(script: Script, target: ScriptTarget = defaultTargetFor(script)) {
        scope.launch { run(script, target) }
    }

    /** 啟動並等到引擎答覆。失敗原因也會記在 [states] 裡給畫面顯示。 */
    suspend fun run(script: Script, target: ScriptTarget = defaultTargetFor(script)): ScriptStartOutcome =
        startMutex.withLock {
            val outcome = launchRun(script, target)
            startFailures.update { failures ->
                when (outcome) {
                    is ScriptStartOutcome.Started -> failures - script.id
                    is ScriptStartOutcome.AlreadyRunning -> failures
                    is ScriptStartOutcome.DisplayBusy ->
                        failures + (script.id to "Display ${outcome.displayId} is running ${outcome.scriptId}")
                    is ScriptStartOutcome.Failed -> failures + (script.id to outcome.message)
                }
            }
            outcome
        }

    private suspend fun launchRun(script: Script, target: ScriptTarget): ScriptStartOutcome {
        if (isRunning(script.id)) return ScriptStartOutcome.AlreadyRunning
        if (script.uniqueId == null) return ScriptStartOutcome.Failed("Script is missing a uniqueId in script.json")

        val result = shizukuManager.withService { service ->
            val displayId = resolveDisplay(service, target)
                ?: return@withService ScriptStartOutcome.Failed("Target display not found")

            val started = ScriptEngine.start(
                context = context,
                service = service,
                run = ScriptRun(
                    scriptId = script.id,
                    scriptDir = script.dir,
                    displayId = displayId,
                    hasVision = target.hasVision,
                    ocr = ocrManager.runtime(),
                ),
                onNotify = { title, text -> notifier.showScriptMessage(title, text) },
                onOpenUri = ::openUri,
            )
            when (started) {
                is ScriptStartResult.Started -> {
                    forwardLog(started.engine)
                    runs.update { it + (script.id to Run(script, displayId, started.engine)) }
                }

                is ScriptStartResult.DisplayBusy ->
                    return@withService ScriptStartOutcome.DisplayBusy(started.displayId, started.scriptId)

                is ScriptStartResult.Failed -> return@withService ScriptStartOutcome.Failed(started.message)
            }
            if (target.hasVision && settings.autoOpenFullscreen.value) openFullscreen(displayId)
            ScriptStartOutcome.Started
        }
        return result.getOrElse { ScriptStartOutcome.Failed("Shizuku service unavailable") }
    }

    /** 只在 [startMutex] 內呼叫。 */
    private fun forwardLog(engine: ScriptEngine) {
        val scriptId = engine.run.scriptId
        logJobs.remove(scriptId)?.cancel()
        logJobs[scriptId] = scope.launch {
            engine.logLines.collect { _logLines.emit(ScriptLogLine(scriptId, it)) }
        }
    }

    fun stop(scriptId: String) {
        scope.launch { runs.value[scriptId]?.engine?.stop() }
    }

    fun stopAll() {
        scope.launch { runs.value.values.forEach { it.engine.stop() } }
    }

    /**
     * 給需要「真的停了才能做下一步」的呼叫端用（見 WorkbenchServer 的 `POST /run/stop`：
     * webview 收到成功回應就會立刻送下一個 start，[states] 還沒翻成非 running 就會被擋下）。
     *
     * [ScriptEngine.stop] 返回時引擎已經停了，但 [states] 是非同步合成的，這裡等它跟上（或逾時）。
     *
     * @param scriptId null 表示全部。
     */
    suspend fun stopAndAwait(scriptId: String? = null, timeoutMs: Long = 3000) {
        val targets = runs.value.filterKeys { scriptId == null || it == scriptId }
        if (targets.keys.none(::isRunning)) return
        targets.values.forEach { it.engine.stop() }
        withTimeoutOrNull(timeoutMs) {
            states.first { map -> targets.keys.none { map[it]?.isRunning == true } }
        }
    }

    /**
     * 把 target 解析成實際的 displayId。
     *
     * [ScriptTarget.NewVirtual] 會先照 [DisplayConfig.name]（腳本的 `uniqueId`）找現有虛擬顯示
     * 再沿用——腳本存的是「要一個屬於自己、長這樣的顯示器」，不是某個必然會過期的 id，
     * 也不該跟另一個剛好同尺寸的腳本共用。
     * 名稱對得上但尺寸/densityDpi 不同（腳本改了 `script.json`）時，resize 既有的那個，
     * 不銷毀重建——保留 displayId，正在依附它的 consumer 才不會斷線。
     */
    internal fun resolveDisplay(service: IMoonClickerService, target: ScriptTarget): Int? =
        resolveDisplayId(service, target)?.also { displayId ->
            // 在腳本啟動 app 之前設定：IME policy 只影響之後才開始的輸入。沿用的既有顯示器上
            // 已經開著的 app 不受影響，要重新啟動才會套用。
            if (target is ScriptTarget.NewVirtual) {
                runCatching { service.setDisplayKeyboardEnabled(displayId, target.config.keyboard) }
                    .onFailure { Timber.e(it, "setDisplayKeyboardEnabled failed") }
            }
        }

    private fun resolveDisplayId(service: IMoonClickerService, target: ScriptTarget): Int? =
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
                        runCatching { service.createVirtualDisplay(target.config, settings.virtualDeviceEnabled.value) }
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

    /**
     * 現有虛擬顯示的建立尺寸與 densityDpi 是否跟腳本要求的一致。
     *
     * 比建立尺寸（[com.xaxaxax.moonclicker.MoonClickerDisplayInfo.surfaceWidth]），不比
     * `getDisplaySurfaceSize`：後者是影格尺寸，VD 上的 app 宣告橫向時會互換長寬。
     * 精確比對，不接受長寬互換：接受的話，以 2400x1080 建立的顯示器會被拿去滿足 1080x2400
     * 的請求，腳本就跑在 surface 幾何相反的顯示器上（見 ADR-0012）。
     */
    private fun matchesConfig(service: IMoonClickerService, displayId: Int, config: DisplayConfig): Boolean {
        val info = runCatching { service.getDisplayInfo(displayId) }.getOrNull() ?: return false
        return info.surfaceWidth == config.width && info.surfaceHeight == config.height &&
            info.densityDpi == config.densityDpi
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
