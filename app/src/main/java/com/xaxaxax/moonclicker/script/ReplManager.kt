package com.xaxaxax.moonclicker.script

import android.content.Context
import com.xaxaxax.moonclicker.core.OcrManager
import com.xaxaxax.moonclicker.engine.ReplResult
import com.xaxaxax.moonclicker.engine.ReplSession
import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.engine.ReplTarget
import com.xaxaxax.moonclicker.notification.ScriptStatusNotifier
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** REPL 開在哪裡。[scriptId] 為 null 時以整個 scripts 資料夾為根。 */
data class ReplInfo(val displayId: Int, val scriptId: String?)

/** [info] 為 null 表示沒有開著的 REPL。[busy] 表示有程式碼正在跑或排隊。 */
data class ReplState(val info: ReplInfo? = null, val busy: Boolean = false)

/** 跑完的一段。[client] 是送出端自己帶的識別。 */
data class ReplChunk(val client: String, val code: String, val result: ReplResult)

/**
 * App 裡唯一的 REPL：VS Code 與 MCP 共用同一個 [ReplSession]，誰開、誰重設都是同一個。
 * 開著時持有 UserService 的租約。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ReplManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val scriptStore: ScriptStore,
    private val notifier: ScriptStatusNotifier,
    private val ocrManager: OcrManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 序列化開、重設、關。 */
    private val lifecycle = Mutex()

    private val session = MutableStateFlow<Pair<ReplInfo, ReplSession>?>(null)
    private var logJob: Job? = null
    private val running = MutableStateFlow(0)

    val state: StateFlow<ReplState> =
        combine(session, running) { current, count -> ReplState(current?.first, count > 0) }
            .stateIn(scope, SharingStarted.Eagerly, ReplState())

    private val _logLines = MutableSharedFlow<String>(extraBufferCapacity = 256)

    /** 目前 REPL 以 `log(...)` 送出的每一行。 */
    val logLines: SharedFlow<String> = _logLines.asSharedFlow()

    private val _chunks = MutableSharedFlow<ReplChunk>(extraBufferCapacity = 64)

    /** 每一段跑完的結果，不論是誰送的。 */
    val chunks: SharedFlow<ReplChunk> = _chunks.asSharedFlow()

    /** 目前 REPL 以 `data.set` 發佈的鍵值；換一個 REPL 時從空的開始。 */
    val sharedData: Flow<Map<String, Any>> =
        session.flatMapLatest { it?.second?.sharedData ?: flowOf(emptyMap()) }

    /** 開一個新的 REPL；已經開著的（不論目標）先關掉，state 不保留。 */
    suspend fun open(displayId: Int, scriptId: String?): Result<ReplInfo> = lifecycle.withLock {
        closeLocked()
        openLocked(ReplInfo(displayId, scriptId))
    }

    /** 以同樣的目標重開，清空 state。沒有開著的 REPL 時失敗。 */
    suspend fun reset(): Result<ReplInfo> = lifecycle.withLock {
        val info = session.value?.first ?: return@withLock Result.failure(IllegalStateException("REPL is not open"))
        closeLocked()
        openLocked(info)
    }

    suspend fun close() = lifecycle.withLock { closeLocked() }

    /** 跑一段程式碼並等結果。沒有開著的 REPL 時回 [ReplStatus.CLOSED]。 */
    suspend fun eval(code: String, client: String): ReplResult {
        val current = session.value?.second ?: return ReplResult(ReplStatus.CLOSED, "REPL is not open")
        running.update { it + 1 }
        val result = try {
            current.eval(code)
        } finally {
            running.update { it - 1 }
        }
        _chunks.emit(ReplChunk(client, code, result))
        return result
    }

    fun interrupt() {
        session.value?.second?.interrupt()
    }

    private suspend fun openLocked(info: ReplInfo): Result<ReplInfo> {
        val baseDir = if (info.scriptId == null) {
            scriptStore.root
        } else {
            ScriptStore.scan(scriptStore.root).find { it.id == info.scriptId }?.dir
                ?: return Result.failure(IllegalArgumentException("Script not found: ${info.scriptId}"))
        }
        val opened = shizukuManager.withService { service ->
            val display = runCatching { service.getDisplayInfo(info.displayId) }.getOrNull()
                ?: return@withService Result.failure<ReplSession>(IllegalArgumentException("Display ${info.displayId} not found"))
            ReplSession.open(
                context = context,
                service = service,
                // 只有服務自己建的虛擬顯示有 distributor，同 ScriptTarget.hasVision。
                target = ReplTarget(info.displayId, baseDir, hasVision = display.isManaged, ocr = ocrManager.runtime()),
                onNotify = { title, text -> notifier.showScriptMessage(title, text) },
                onOpenUri = context::openIntentUri,
            ).onSuccess { shizukuManager.leases.acquire() }
        }.getOrElse { Result.failure(IllegalStateException("Shizuku service unavailable")) }

        return opened.map { repl ->
            logJob = scope.launch { repl.logLines.collect { _logLines.emit(it) } }
            session.value = info to repl
            info
        }
    }

    private fun closeLocked() {
        val (_, repl) = session.value ?: return
        session.value = null
        repl.close()
        logJob?.cancel()
        logJob = null
        shizukuManager.leases.release()
    }
}
