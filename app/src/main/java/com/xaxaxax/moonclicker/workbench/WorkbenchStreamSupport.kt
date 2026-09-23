package com.xaxaxax.moonclicker.workbench

import io.ktor.websocket.Frame
import java.io.File
import moonclicker.workbench.FileChangeEvent
import moonclicker.workbench.StreamEvent

/**
 * `StreamEvent{log=...}` 編碼成二進位 proto frame——形狀來自 `proto/workbench_stream_event.proto`，
 * 跟 extension 端的 `@bufbuild/protobuf` 生成型別是同一份 schema，不用兩邊手動同步解析。
 */
internal fun logFrame(line: String): Frame =
    Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(log = line)))

/**
 * 每次變動送整個 map，不算 diff。`data` 的值理應只有 Double/String/Boolean，但
 * `ScriptEngine.sharedData` 型別是 `Map<String, Any>`，這個假設不是型別系統保證的——
 * Wire 的 Struct 編碼只接受 null/Boolean/Double/String/List/Map，其餘一律
 * `IllegalArgumentException`，未知型別因此在送出前先轉成字串，不讓一個不符預期的值
 * 讓整個 WebSocket session 崩潰。
 */
internal fun dataFrame(data: Map<String, Any>): Frame =
    Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(data_ = data.mapValues { (_, value) -> value.toStructValue() })))

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
