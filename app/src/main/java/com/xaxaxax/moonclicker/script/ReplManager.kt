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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 一個 REPL 開在哪裡。[id] 由 [ReplManager] 配發，reset 後不變。[scriptId] 為 null 時以整個 scripts 資料夾為根。 */
data class ReplInfo(val id: String, val displayId: Int, val scriptId: String?)

/** [busy] 表示有程式碼正在跑或排隊。 */
data class ReplState(val info: ReplInfo, val busy: Boolean = false)

/** [replId] 的 REPL 以 `log(...)` 送出的一行。 */
data class ReplLogLine(val replId: String, val line: String)

/** 跑完的一段。[client] 是送出端自己帶的識別。 */
data class ReplChunk(val replId: String, val client: String, val code: String, val result: ReplResult)

class ReplLimitException : IllegalStateException("At most ${ReplManager.MAX_REPLS} REPLs can be open at once")

/**
 * App 裡所有開著的 REPL，各自一個 [ReplSession]、互不影響。VS Code 與 MCP 預設各開各的，
 * 也可以接上別人開的那個（以 id 指定）。每個開著的 REPL 持有一份 UserService 租約。
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
    private class Entry(val info: ReplInfo, val session: ReplSession, val logJob: Job)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 序列化開、重設、關。 */
    private val lifecycle = Mutex()
    private var nextId = 1

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val running = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 所有開著的 REPL，依開啟順序。 */
    val state: StateFlow<List<ReplState>> =
        combine(entries, running) { current, counts ->
            current.values.map { ReplState(it.info, (counts[it.info.id] ?: 0) > 0) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _logLines = MutableSharedFlow<ReplLogLine>(extraBufferCapacity = 256)
    val logLines: SharedFlow<ReplLogLine> = _logLines.asSharedFlow()

    private val _chunks = MutableSharedFlow<ReplChunk>(extraBufferCapacity = 64)

    /** 每一段跑完的結果，不論是哪個 REPL、誰送的。 */
    val chunks: SharedFlow<ReplChunk> = _chunks.asSharedFlow()

    /** 每個 REPL 以 `data.set` 發佈的鍵值，key 是 REPL id；reset 後從空的開始。 */
    val sharedData: Flow<Map<String, Map<String, Any>>> =
        entries.flatMapLatest { current ->
            if (current.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(current.values.map { entry -> entry.session.sharedData.map { entry.info.id to it } }) { it.toMap() }
            }
        }

    /**
     * 開一個新的 REPL，不影響已經開著的。已經開了 [MAX_REPLS] 個時以 [ReplLimitException] 失敗：
     * 每個 REPL 是一條 Lua 執行緒，有影格來源時還各掛一個 sink。
     */
    suspend fun open(displayId: Int, scriptId: String?): Result<ReplInfo> = lifecycle.withLock {
        if (entries.value.size >= MAX_REPLS) return@withLock Result.failure(ReplLimitException())
        openLocked(ReplInfo((nextId++).toString(), displayId, scriptId))
    }

    /** 以同樣的 id 與目標重開，清空 state。沒有這個 REPL 時回 null。 */
    suspend fun reset(id: String): Result<ReplInfo>? = lifecycle.withLock {
        val info = entries.value[id]?.info ?: return@withLock null
        closeLocked(id)
        openLocked(info)
    }

    /** 沒有這個 REPL 時回 false。 */
    suspend fun close(id: String): Boolean = lifecycle.withLock { closeLocked(id) }

    /** 跑一段程式碼並等結果。沒有這個 REPL 時回 [ReplStatus.CLOSED]。 */
    suspend fun eval(id: String, code: String, client: String): ReplResult {
        val entry = entries.value[id] ?: return ReplResult(ReplStatus.CLOSED, "REPL $id is not open")
        running.update { it + (id to (it[id] ?: 0) + 1) }
        val result = try {
            entry.session.eval(code)
        } finally {
            running.update { counts ->
                val left = (counts[id] ?: 1) - 1
                if (left > 0) counts + (id to left) else counts - id
            }
        }
        _chunks.emit(ReplChunk(id, client, code, result))
        return result
    }

    /** 沒有這個 REPL 時回 false。 */
    fun interrupt(id: String): Boolean {
        val entry = entries.value[id] ?: return false
        entry.session.interrupt()
        return true
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
            val logJob = scope.launch { repl.logLines.collect { _logLines.emit(ReplLogLine(info.id, it)) } }
            entries.update { it + (info.id to Entry(info, repl, logJob)) }
            info
        }
    }

    companion object {
        const val MAX_REPLS = 4
    }

    private fun closeLocked(id: String): Boolean {
        val entry = entries.value[id] ?: return false
        entries.update { it - id }
        entry.session.close()
        entry.logJob.cancel()
        shizukuManager.leases.release()
        return true
    }
}
