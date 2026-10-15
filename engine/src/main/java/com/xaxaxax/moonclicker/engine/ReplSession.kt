package com.xaxaxax.moonclicker.engine

import android.content.Context
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.lua.LuaNative
import com.xaxaxax.moonclicker.ocr.OcrRuntime
import com.xaxaxax.moonclicker.script.ScriptHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** REPL 要開在哪裡。 */
data class ReplTarget(
    /** 目標顯示器。0 是實體螢幕。 */
    val displayId: Int,
    /** `require` 與模板路徑的根。 */
    val baseDir: File,
    /** 是否掛影格來源，同 [ScriptRun.hasVision]。 */
    val hasVision: Boolean,
    val ocr: OcrRuntime? = null,
)

/** 一段程式碼的結果。[code] 與 native 的 `ReplStatus` 一致。 */
enum class ReplStatus(val code: Int) {
    OK(0),
    ERROR(1),

    /** 語法上還沒寫完，等使用者補完再整段送出（像官方 `lua` 的 `>>` 續行）。 */
    INCOMPLETE(2),

    /** 被 [ReplSession.interrupt] 打斷；state 保留。 */
    INTERRUPTED(3),

    /** 還沒跑完 REPL 就被關掉了。 */
    CLOSED(-1);

    companion object {
        fun of(code: Int): ReplStatus = entries.firstOrNull { it.code == code } ?: ERROR
    }
}

/** [output] 在 [ReplStatus.OK] 時是回傳值（以 tab 串起，沒有回傳值時為空字串），其餘是錯誤訊息。 */
data class ReplResult(val status: ReplStatus, val output: String)

/**
 * 獨立於腳本的持久 Lua session：自己的 Lua state 與執行緒，API 與腳本相同。全域變數保留到 [close]；
 * 每段的 `local` 不保留，與官方 `lua` REPL 相同。[interrupt] 只打斷當下這一段。
 *
 * 不佔用顯示器：同一個顯示器上可以同時有腳本在跑。
 */
class ReplSession private constructor(val target: ReplTarget) {

    private val _sharedData = MutableStateFlow<Map<String, Any>>(emptyMap())

    /** 以 `data.set` 發佈的鍵值。 */
    val sharedData: StateFlow<Map<String, Any>> = _sharedData.asStateFlow()

    private val _logLines = MutableSharedFlow<String>(extraBufferCapacity = 256)

    /** 以 `log(...)` 送出的每一行。 */
    val logLines: SharedFlow<String> = _logLines.asSharedFlow()

    private val _isOpen = MutableStateFlow(true)

    /** native 執行緒還在。啟動失敗、[close] 之後為 false。 */
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val nextId = AtomicLong(1)
    private val pending = HashMap<Long, CompletableDeferred<ReplResult>>()

    /** native ScriptRuntime（REPL 模式）的指標；0 表示沒有。以 `this` 為鎖。 */
    private var handle = 0L
    private var host: ScriptHost? = null

    /** 排入一段程式碼並等它跑完。多段依序執行。REPL 已關閉時回 [ReplStatus.CLOSED]。 */
    suspend fun eval(code: String): ReplResult {
        val result = CompletableDeferred<ReplResult>()
        synchronized(this) {
            if (handle == 0L) return ReplResult(ReplStatus.CLOSED, "REPL is closed")
            val id = nextId.getAndIncrement()
            pending[id] = result
            LuaNative.nativeReplEval(handle, id, code.encodeToByteArray())
        }
        return result.await()
    }

    /** 打斷正在執行的那一段；沒有在跑時不做事。 */
    fun interrupt() {
        synchronized(this) {
            if (handle != 0L) LuaNative.nativeReplInterrupt(handle)
        }
    }

    /** 停止並等 Lua 執行緒結束，然後放開觸控、歸還鏡像。還在等的 [eval] 收到 [ReplStatus.CLOSED]。可重入。 */
    fun close() {
        val (stopping, closingHost) = synchronized(this) {
            (handle to host).also {
                handle = 0L
                host = null
            }
        }
        // 不持鎖等執行緒結束：Lua 執行緒回報結果時要拿這把鎖。
        if (stopping != 0L) LuaNative.nativeStop(stopping)
        // native 已經停了、reader 也拿掉了，這時才放開觸控、歸還鏡像。
        closingHost?.close()
        val waiting = synchronized(this) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.complete(ReplResult(ReplStatus.CLOSED, "REPL was closed")) }
        _isOpen.value = false
    }

    private fun onResult(id: Long, status: Int, output: String) {
        val deferred = synchronized(this) { pending.remove(id) }
        deferred?.complete(ReplResult(ReplStatus.of(status), output))
    }

    private fun launch(
        context: Context,
        service: IMoonClickerService,
        onNotify: (String, String) -> Unit,
        onOpenUri: (String) -> Unit,
    ): String? {
        val geometry = DisplayLaunchGeometry.read(context, service, target.displayId)
            ?: return "Could not read the surface size of display ${target.displayId}"

        val replHost = ScriptHost(
            service = service,
            displayId = target.displayId,
            onNotify = onNotify,
            onOpenUri = onOpenUri,
            onData = { key, value ->
                _sharedData.update { current -> if (value == null) current - key else current + (key to value) }
            },
            onLog = { line -> _logLines.tryEmit(line) },
            // REPL 沒有跑完或出錯的終點，開關以 isOpen 表示。
            onEvent = { _, _ -> },
            onReplResult = ::onResult,
        )

        synchronized(this) {
            val started = LuaNative.nativeStartRepl(
                replHost,
                target.displayId,
                geometry.isPhysical,
                target.hasVision,
                geometry.surfaceWidth,
                geometry.surfaceHeight,
                geometry.rotation,
                target.baseDir.absolutePath,
                target.ocr?.packDir?.absolutePath.orEmpty(),
                target.ocr?.threads ?: 0,
            )
            if (started == 0L) return "Native engine refused to start the REPL"
            handle = started
            host = replHost
        }
        return null
    }

    companion object {
        /** 開一個新的 REPL。失敗時回傳原因。 */
        fun open(
            context: Context,
            service: IMoonClickerService,
            target: ReplTarget,
            onNotify: (title: String, text: String) -> Unit,
            onOpenUri: (uri: String) -> Unit,
        ): Result<ReplSession> {
            val session = ReplSession(target)
            val failure = session.launch(context, service, onNotify, onOpenUri)
            if (failure != null) {
                session.close()
                return Result.failure(IllegalStateException(failure))
            }
            return Result.success(session)
        }
    }
}
