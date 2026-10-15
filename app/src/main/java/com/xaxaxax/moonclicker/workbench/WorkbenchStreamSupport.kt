package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.script.ReplChunk
import com.xaxaxax.moonclicker.script.ReplLogLine
import com.xaxaxax.moonclicker.script.ReplState
import io.ktor.websocket.Frame
import java.io.File
import moonclicker.workbench.DataEvent
import moonclicker.workbench.FileChangeEvent
import moonclicker.workbench.LogEvent
import moonclicker.workbench.ReplChunkEvent
import moonclicker.workbench.ReplEvent
import moonclicker.workbench.ReplListEvent
import moonclicker.workbench.ReplStateEvent
import moonclicker.workbench.RunStateEvent
import moonclicker.workbench.StreamEvent

/**
 * `StreamEvent` 編碼成二進位 proto frame——形狀來自 `proto/workbench_stream_event.proto`，
 * 跟 extension 端的 `@bufbuild/protobuf` 生成型別是同一份 schema，不用兩邊手動同步解析。
 */
internal fun logFrame(scriptId: String, line: String): Frame =
    Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(log = LogEvent(script_id = scriptId, line = line))))

/**
 * [previous] 是這條連線上次送出的 data，null 表示還沒送過，這時送 reset 的完整快照；
 * 之後只送差異，沒有差異回 null。
 *
 * `data` 的值理應只有 Double/String/Boolean，但型別是 `Map<String, Any>`，這個假設不是型別系統保證的——
 * Wire 的 Struct 編碼只接受 null/Boolean/Double/String/List/Map，其餘一律
 * `IllegalArgumentException`，未知型別因此在送出前先轉成字串，不讓一個不符預期的值
 * 讓整個 WebSocket session 崩潰。
 */
internal fun dataFrame(scriptId: String, previous: Map<String, Any>?, current: Map<String, Any>): Frame? =
    dataEvent(scriptId, previous, current)?.let { Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(data_ = it))) }

/** 同 [dataFrame]，給 REPL 包進 [ReplEvent]；`DataEvent.script_id` 也填 REPL id。 */
internal fun replDataFrame(replId: String, previous: Map<String, Any>?, current: Map<String, Any>): Frame? =
    dataEvent(replId, previous, current)?.let { replFrame(ReplEvent(repl_id = replId, data_ = it)) }

private fun dataEvent(scriptId: String, previous: Map<String, Any>?, current: Map<String, Any>): DataEvent? =
    if (previous == null) {
        DataEvent(script_id = scriptId, reset = true, values = current.mapValues { (_, value) -> value.toStructValue() })
    } else {
        val changed = current.filter { (key, value) -> previous[key] != value }
        val removed = previous.keys - current.keys
        if (changed.isEmpty() && removed.isEmpty()) {
            null
        } else {
            DataEvent(
                script_id = scriptId,
                values = changed.mapValues { (_, value) -> value.toStructValue() },
                removed = removed.toList(),
            )
        }
    }

private fun Any.toStructValue(): Any = when (this) {
    is Double, is Boolean, is String -> this
    else -> toString()
}

internal fun fileChangeFrame(change: ScriptFileChange): Frame =
    Frame.Binary(
        true,
        StreamEvent.ADAPTER.encode(
            StreamEvent(
                file_change = FileChangeEvent(
                    script_id = change.scriptId,
                    path = change.path,
                    kind = when (change.kind) {
                        ScriptFileChangeKind.CREATED -> FileChangeEvent.Kind.CREATED
                        ScriptFileChangeKind.CHANGED -> FileChangeEvent.Kind.CHANGED
                        ScriptFileChangeKind.DELETED -> FileChangeEvent.Kind.DELETED
                    },
                )
            )
        )
    )

internal fun runStateFrame(runningScriptIds: List<String>): Frame =
    Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(run_state = RunStateEvent(running_script_ids = runningScriptIds))))

internal fun replListFrame(states: List<ReplState>): Frame =
    replFrame(
        ReplEvent(
            list = ReplListEvent(
                repls = states.map {
                    ReplStateEvent(
                        id = it.info.id,
                        display_id = it.info.displayId,
                        script_id = it.info.scriptId.orEmpty(),
                        busy = it.busy,
                    )
                },
            )
        )
    )

internal fun replLogFrame(line: ReplLogLine): Frame = replFrame(ReplEvent(repl_id = line.replId, log = line.line))

internal fun replChunkFrame(chunk: ReplChunk): Frame =
    replFrame(
        ReplEvent(
            repl_id = chunk.replId,
            chunk = ReplChunkEvent(
                client = chunk.client,
                code = chunk.code,
                status = when (chunk.result.status) {
                    ReplStatus.OK -> ReplChunkEvent.Status.OK
                    ReplStatus.ERROR -> ReplChunkEvent.Status.ERROR
                    ReplStatus.INCOMPLETE -> ReplChunkEvent.Status.INCOMPLETE
                    ReplStatus.INTERRUPTED -> ReplChunkEvent.Status.INTERRUPTED
                    ReplStatus.CLOSED -> ReplChunkEvent.Status.CLOSED
                },
                output = chunk.result.output,
            )
        )
    )

private fun replFrame(event: ReplEvent): Frame = Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(repl = event)))

internal fun File.sha256Hex(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** `{path...}` 這種 tail wildcard 路由參數，Ktor 拆成多個 segment，這裡合併回一段相對路徑。 */
internal fun io.ktor.server.application.ApplicationCall.filePathParam(): String =
    parameters.getAll("path")?.joinToString("/").orEmpty()
