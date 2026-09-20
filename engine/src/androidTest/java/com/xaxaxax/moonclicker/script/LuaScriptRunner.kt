package com.xaxaxax.moonclicker.script

import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.engine.ScriptEngine
import com.xaxaxax.moonclicker.engine.ScriptRun
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.engine.state.EngineStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 一次執行的全部產出，測試對著它斷言。 */
internal data class ScriptOutcome(
    val runState: EngineRunState,
    /** 腳本用 `data.set` 發佈的東西——腳本自己回報結果的管道。 */
    val data: Map<String, Any>,
    val calls: List<RecordingMoonClickerService.Call>,
    val notifications: List<Pair<String, String>>,
    val openedUris: List<String>,
    val logLines: List<String>,
) {
    val error: String? get() = (runState as? EngineRunState.Error)?.message
}

/**
 * 把一段 Lua 原始碼跑成一份 [ScriptOutcome]。
 *
 * 斷言有三個管道，測試依要驗的東西挑：
 *
 *  1. **[ScriptOutcome.data]** —— 腳本用 `data.set` 自己講。凡是 Lua 看得見的事實
 *     （`screen.width`、`vision.find` 回什麼、某個分支有沒有走到）都能這樣驗，不必為每
 *     一個新 API 加新的測試管線。
 *  2. **[ScriptOutcome.runState]** —— `Finished` / `Error(訊息)` / `Stopped`。驗「這個
 *     呼叫該不該炸」與「停止是不是乾淨地展開」。
 *  3. **[ScriptOutcome.calls]** —— [RecordingMoonClickerService] 記下的服務呼叫。驗座標、
 *     duration、pointer id 這些送到邊界上的實際值。
 *
 * 每個 runner 只跑一份腳本；原生引擎是單例，所以測試之間一定要 [close]。
 */
internal class LuaScriptRunner(
    /** Tier 0 用 [RecordingMoonClickerService]；Tier 1 傳真的 `MoonClickerService` 進來。 */
    val service: IMoonClickerService = RecordingMoonClickerService(),
    /** 0 是實體螢幕。Tier 0 不建立顯示器，所以就用它。 */
    private val displayId: Int = 0,
    /** Tier 0 固定 false：沒有真實影格來源，`vision.*` 應當明確報錯。 */
    private val hasVision: Boolean = false,
) : AutoCloseable {

    /** 以 [RecordingMoonClickerService] 執行時記下來的呼叫。Tier 1 用不到。 */
    val recorded: RecordingMoonClickerService get() = service as RecordingMoonClickerService

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = CopyOnWriteArrayList<Pair<String, String>>()
    private val openedUris = CopyOnWriteArrayList<String>()
    private val logLines = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dir: File? = null

    /** 寫出腳本、啟動、等到終態。 */
    fun run(
        main: String,
        files: Map<String, String> = emptyMap(),
        assets: Map<String, ByteArray> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ScriptOutcome {
        start(main, files, assets)
        return await(timeoutMs)
    }

    /** 啟動後立刻返回——要在腳本執行中做事（例如中途 [stop]）時用這個。 */
    fun start(
        main: String,
        files: Map<String, String> = emptyMap(),
        /** 模板圖之類的二進位檔，和 `main.lua` 放在同一個資料夾裡。 */
        assets: Map<String, ByteArray> = emptyMap(),
    ) {
        val folder = File(context.cacheDir, "lua-test/${System.nanoTime()}")
        check(folder.mkdirs()) { "could not create script folder $folder" }
        dir = folder

        File(folder, "main.lua").writeText(main)
        files.forEach { (name, body) ->
            File(folder, name).apply { parentFile?.mkdirs() }.writeText(body)
        }
        assets.forEach { (name, bytes) ->
            File(folder, name).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }

        // UNDISPATCHED：保證 collect 真的掛上 SharedFlow 才讓這行 launch 返回，避免腳本執行緒
        // 在訂閱建立前就把最早幾行 log 發出去（SharedFlow 沒有 replay，emit 時没人訂閱就遺失）。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ScriptEngine.logLines.collect { logLines += it }
        }

        val started = ScriptEngine.start(
            context = context,
            service = service,
            run = ScriptRun(
                scriptId = folder.name,
                scriptDir = folder,
                displayId = displayId,
                hasVision = hasVision,
            ),
            onNotify = { title, text -> notifications += title to text },
            onOpenUri = { openedUris += it },
        )
        check(started) { "ScriptEngine refused to start; the previous test probably leaked a run" }
    }

    /** 等到 `Finished` / `Error` / `Stopped`。逾時就讓測試以 TimeoutCancellationException 失敗。 */
    fun await(timeoutMs: Long = DEFAULT_TIMEOUT_MS): ScriptOutcome = runBlocking {
        val state = withTimeout(timeoutMs) {
            EngineStateRepository.state.first { it.runState.isTerminal }
        }
        ScriptOutcome(
            runState = state.runState,
            data = ScriptEngine.sharedData.value,
            calls = (service as? RecordingMoonClickerService)?.calls?.toList() ?: emptyList(),
            notifications = notifications.toList(),
            openedUris = openedUris.toList(),
            logLines = logLines.toList(),
        )
    }

    /** 從外部停止執行中的腳本，模擬使用者按下停止。 */
    fun stop() = ScriptEngine.stop()

    override fun close() {
        ScriptEngine.stop()
        scope.cancel()
        dir?.deleteRecursively()
        dir = null
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}

internal val EngineRunState.isTerminal: Boolean
    get() = this is EngineRunState.Finished ||
            this is EngineRunState.Error ||
            this is EngineRunState.Stopped
