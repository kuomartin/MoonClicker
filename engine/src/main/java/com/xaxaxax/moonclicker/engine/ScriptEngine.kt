package com.xaxaxax.moonclicker.engine

import android.content.Context
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.engine.state.EngineEventType
import com.xaxaxax.moonclicker.engine.state.EngineState
import com.xaxaxax.moonclicker.engine.state.EngineStateRepository
import com.xaxaxax.moonclicker.lua.LuaNative
import com.xaxaxax.moonclicker.ocr.OcrRuntime
import com.xaxaxax.moonclicker.script.ScriptHost
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.File
import kotlin.concurrent.thread

/** 一次執行要跑的東西：哪份腳本、跑在哪個顯示器上。 */
data class ScriptRun(
    /** 給 UI 對照用的識別，[EngineState] 會把它一起帶著。 */
    val scriptId: String,
    /** 腳本資料夾，內含 `main.lua`；模板路徑相對於它解析。 */
    val scriptDir: File,
    /** 目標顯示器。0 是實體螢幕。 */
    val displayId: Int,
    /**
     * 是否掛影格來源。只有 [IMoonClickerService] 自己建立的虛擬顯示拿得到影格，
     * 實體螢幕必須是 false——那時 `vision.*` 會在 Lua 端明確報錯。
     */
    val hasVision: Boolean,
    /** 已安裝的 OCR 套件；`null` 表示未安裝或不支援，腳本用到 OCR 時報錯。 */
    val ocr: OcrRuntime? = null,
)

/** [ScriptEngine.start] 的結果。 */
sealed interface ScriptStartResult {
    data class Started(val engine: ScriptEngine) : ScriptStartResult

    /** 目標顯示器上已經有腳本在跑。一個顯示器同時只跑一支（實體螢幕也算一個）。 */
    data class DisplayBusy(val displayId: Int, val scriptId: String) : ScriptStartResult

    data class Failed(val message: String) : ScriptStartResult
}

/**
 * `:engine` 對外的腳本執行門面，一個實例就是一次執行：自己的 Lua state、執行緒、狀態、log 與 `data`。
 * 其他模組只透過這裡啟停引擎，不碰 `LuaNative`（它是 `internal`，編譯器會擋）。
 *
 * 不同顯示器上的實例互不相干，數量不設上限，彼此也不能溝通。腳本跑完、出錯或被停止後，
 * 實例會自己收尾（拿掉取影格的 surface、放開觸控、歸還鏡像）並讓出顯示器；之後
 * [state]、[sharedData] 仍保留最後的內容。
 *
 * 執行狀態不要輪詢——觀察 [state]。
 */
class ScriptEngine private constructor(val run: ScriptRun) {

    private val repository = EngineStateRepository(run.scriptId)

    val state: StateFlow<EngineState> = repository.state

    val isRunning: Boolean get() = !state.value.runState.isTerminal

    private val _sharedData = MutableStateFlow<Map<String, Any>>(emptyMap())

    /** 腳本透過 Lua 的 `data.set` 發佈的鍵值。 */
    val sharedData: StateFlow<Map<String, Any>> = _sharedData.asStateFlow()

    // replay：呼叫端拿到實例時腳本可能已經印了幾行。
    private val _logLines = MutableSharedFlow<String>(replay = LOG_REPLAY, extraBufferCapacity = 64)

    /** 腳本透過 Lua 的 `log(...)` 送出的每一行。新的訂閱者會先收到最近 [LOG_REPLAY] 行。 */
    val logLines: SharedFlow<String> = _logLines.asSharedFlow()

    /** native ScriptRuntime 的指標；0 表示沒有。以 `this` 為鎖。 */
    private var handle = 0L
    private var host: ScriptHost? = null
    private var closed = false

    /** 停止並等 Lua 執行緒結束，然後收尾、讓出顯示器。可重入，已結束時直接返回。 */
    fun stop() {
        synchronized(this) {
            if (closed) return
            closed = true
            if (handle != 0L) LuaNative.nativeStop(handle)
            handle = 0L
            // native 已經停了、reader 也拿掉了，這時才放開觸控、歸還鏡像。
            host?.close()
            host = null
        }
        unregister(this)
    }

    private fun onEvent(type: Int, payload: String) {
        repository.onEvent(type, payload)
        // 事件來自 Lua 執行緒；stop() 要 join 它，所以換條執行緒收尾。
        if (state.value.runState.isTerminal) thread(name = "ScriptEngine-cleanup") { stop() }
    }

    private fun launch(context: Context, service: IMoonClickerService, onNotify: (String, String) -> Unit, onOpenUri: (String) -> Unit): String? {
        // AImageReader 開成 distributor 目前輸出的影格尺寸（ADR-0017：轉正後的自然尺寸，
        // 已經隨 v 互換長寬）——問服務要這個值，不自己從（邏輯尺寸, rotation）回推。
        // 緩衝區一開就是整場執行，中途 VD 再旋轉不會跟著變，見 ADR-0017 的 Consequences。
        val surface = try {
            service.getDisplaySurfaceSize(run.displayId)
        } catch (t: Throwable) {
            Timber.e(t, "getDisplaySurfaceSize(${run.displayId}) failed")
            null
        }
        if (surface == null || surface.size < 2 || surface[0] <= 0 || surface[1] <= 0) {
            // [0, 0] 也代表「這個 id 不是服務建的顯示器」。與其用推出來的幾何硬跑，
            // 不如當場停下來講清楚。
            return "Could not read the surface size of display ${run.displayId}"
        }
        val (surfaceWidth, surfaceHeight) = surface[0] to surface[1]

        val rotation = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(run.displayId)?.rotation ?: 0

        val displayInfo = runCatching { service.getDisplayInfo(run.displayId) }.getOrNull()
        val isPhysical = displayInfo?.isPhysical ?: (run.displayId == 0)

        val scriptHost = ScriptHost(
            service = service,
            displayId = run.displayId,
            onNotify = onNotify,
            onOpenUri = onOpenUri,
            onData = { key, value ->
                _sharedData.update { current ->
                    if (value == null) current - key else current + (key to value)
                }
            },
            // native 執行緒同步呼叫進來，不是 suspend context，emit 會掛住呼叫端。
            onLog = { line -> _logLines.tryEmit(line) },
            onEvent = ::onEvent,
        )

        // 持鎖到 handle 寫入：腳本若立刻結束，收尾的 stop() 要等到這裡做完才看得到 handle。
        synchronized(this) {
            val started = LuaNative.nativeStart(
                scriptHost,
                run.displayId,
                isPhysical,
                run.hasVision,
                surfaceWidth,
                surfaceHeight,
                // 純粹給 Lua screen.rotation 讀的中繼資料，不影響任何座標換算，見 nativeStart 的文件。
                rotation,
                run.scriptDir.absolutePath,
                run.ocr?.packDir?.absolutePath.orEmpty(),
                run.ocr?.threads ?: 0,
            )
            if (started == 0L) {
                return (state.value.runState as? EngineRunState.Error)?.message
                    ?: "Native engine refused to start ${run.scriptDir.name}"
            }
            handle = started
            host = scriptHost
        }
        return null
    }

    companion object {
        const val LOG_REPLAY = 64

        private val _instances = MutableStateFlow<List<ScriptEngine>>(emptyList())

        /** 還沒收尾的實例，依啟動順序。 */
        val instances: StateFlow<List<ScriptEngine>> = _instances.asStateFlow()

        val isAnyRunning: Boolean get() = instances.value.any { it.isRunning }

        /**
         * 在 [ScriptRun.displayId] 上啟動一份腳本。
         *
         * 顯示器的生命週期**不屬於**引擎：這裡只會把取影格用的 surface 掛上去，結束時再拿掉，
         * 顯示器本身留給 :app 的 Displays 頁處置。
         */
        fun start(
            context: Context,
            service: IMoonClickerService,
            run: ScriptRun,
            onNotify: (title: String, text: String) -> Unit,
            onOpenUri: (uri: String) -> Unit,
        ): ScriptStartResult {
            val engine = ScriptEngine(run)
            synchronized(_instances) {
                val occupant = _instances.value.firstOrNull { it.run.displayId == run.displayId }
                if (occupant != null) {
                    // 已經結束、只差收尾的就地收掉，不讓它擋住下一次執行。
                    if (occupant.isRunning) {
                        Timber.w("start(${run.scriptId}) rejected: display ${run.displayId} runs ${occupant.run.scriptId}")
                        return ScriptStartResult.DisplayBusy(run.displayId, occupant.run.scriptId)
                    }
                    occupant.stop()
                }
                _instances.update { it + engine }
            }

            val failure = engine.launch(context, service, onNotify, onOpenUri)
            if (failure != null) {
                engine.repository.onEvent(EngineEventType.ERROR, failure)
                engine.stop()
                return ScriptStartResult.Failed(failure)
            }
            return ScriptStartResult.Started(engine)
        }

        private fun unregister(engine: ScriptEngine) {
            synchronized(_instances) { _instances.update { it - engine } }
        }
    }
}

private val EngineRunState.isTerminal: Boolean
    get() = this is EngineRunState.Finished ||
            this is EngineRunState.Error ||
            this is EngineRunState.Stopped
