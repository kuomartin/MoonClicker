package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ReplResult
import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.script.ReplChunk
import com.xaxaxax.moonclicker.script.ReplInfo
import com.xaxaxax.moonclicker.script.ReplLimitException
import com.xaxaxax.moonclicker.script.ReplLogLine
import com.xaxaxax.moonclicker.script.ReplState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** REPL 需要的最小介面，理由同 [ScriptRunner]。真正實作轉呼叫 [com.xaxaxax.moonclicker.script.ReplManager]。 */
interface ReplRunner {
    val state: StateFlow<List<ReplState>>
    val logLines: Flow<ReplLogLine>
    val chunks: Flow<ReplChunk>
    val sharedData: Flow<Map<String, Map<String, Any>>>
    suspend fun open(displayId: Int, scriptId: String?): Result<ReplInfo>
    /** 沒有這個 REPL 時回 null。 */
    suspend fun reset(id: String): Result<ReplInfo>?
    suspend fun close(id: String): Boolean
    suspend fun eval(id: String, code: String, client: String): ReplResult
    fun interrupt(id: String): Boolean
}

/** `POST /repl/open` 的 body。[scriptId] 是 `require` 與模板路徑的根，省略時是整個 scripts 資料夾。 */
@Serializable
data class ReplOpenRequest(val displayId: Int, val scriptId: String? = null)

/** `POST /repl/eval` 的 body。[client] 是送出端自己的識別，會隨 `ReplChunkEvent` 推播回來。 */
@Serializable
data class ReplEvalRequest(val code: String, val client: String = "")

@Serializable
enum class ReplResultStatus {
    @SerialName("ok") OK,
    @SerialName("error") ERROR,
    @SerialName("incomplete") INCOMPLETE,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("closed") CLOSED,
}

/** `POST /repl/eval` 的回應。 */
@Serializable
data class ReplEvalResponse(val status: ReplResultStatus, val output: String)

/** 一個 REPL 的狀態：`GET /repl` 的每一筆，也是 open/reset 的回應。 */
@Serializable
data class ReplStatusResponse(
    val id: String,
    val displayId: Int,
    val scriptId: String? = null,
    val busy: Boolean,
)

internal fun ReplState.toResponse() =
    ReplStatusResponse(id = info.id, displayId = info.displayId, scriptId = info.scriptId, busy = busy)

internal fun ReplStatus.toResponse(): ReplResultStatus = when (this) {
    ReplStatus.OK -> ReplResultStatus.OK
    ReplStatus.ERROR -> ReplResultStatus.ERROR
    ReplStatus.INCOMPLETE -> ReplResultStatus.INCOMPLETE
    ReplStatus.INTERRUPTED -> ReplResultStatus.INTERRUPTED
    ReplStatus.CLOSED -> ReplResultStatus.CLOSED
}

/**
 * REPL 的開關與執行。每個 REPL 以 open 時配發的 id 指定；誰都可以對任何一個 REPL 送程式碼，
 * 這樣 VS Code 才能接上 MCP 開的那個。
 */
fun Route.replRoutes(repl: ReplRunner) {
    get("/repl") {
        call.respondJson(Json.encodeToString(repl.state.value.map { it.toResponse() }))
    }

    post("/repl/open") {
        val body = runCatching { Json.decodeFromString<ReplOpenRequest>(call.receiveText()) }.getOrNull()
        if (body == null) {
            call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        call.respondOpened(repl.open(body.displayId, body.scriptId), repl)
    }

    get("/repl/{id}") {
        val state = repl.state.value.find { it.info.id == call.parameters["id"] }
        if (state == null) call.respondNotOpen() else call.respondJson(Json.encodeToString(state.toResponse()))
    }

    post("/repl/{id}/reset") {
        val result = repl.reset(call.parameters["id"]!!)
        if (result == null) call.respondNotOpen() else call.respondOpened(result, repl)
    }

    post("/repl/{id}/close") {
        if (repl.close(call.parameters["id"]!!)) call.respondText("Closed") else call.respondNotOpen()
    }

    // 回應等到這段跑完才送出；要中斷就另外送 /repl/{id}/interrupt。
    post("/repl/{id}/eval") {
        val body = runCatching { Json.decodeFromString<ReplEvalRequest>(call.receiveText()) }.getOrNull()
        if (body == null) {
            call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        val id = call.parameters["id"]!!
        if (repl.state.value.none { it.info.id == id }) {
            call.respondNotOpen()
            return@post
        }
        val result = repl.eval(id, body.code, body.client)
        call.respondJson(Json.encodeToString(ReplEvalResponse(result.status.toResponse(), result.output)))
    }

    post("/repl/{id}/interrupt") {
        if (repl.interrupt(call.parameters["id"]!!)) call.respondText("Interrupted") else call.respondNotOpen()
    }
}

private suspend fun ApplicationCall.respondNotOpen() =
    respondText("REPL ${parameters["id"]} is not open", status = HttpStatusCode.NotFound)

private suspend fun ApplicationCall.respondOpened(result: Result<ReplInfo>, repl: ReplRunner) {
    result.fold(
        onSuccess = { info ->
            val busy = repl.state.value.find { it.info.id == info.id }?.busy ?: false
            respondJson(Json.encodeToString(ReplStatusResponse(info.id, info.displayId, info.scriptId, busy)))
        },
        onFailure = { error ->
            val status = when (error) {
                is IllegalArgumentException -> HttpStatusCode.NotFound
                is ReplLimitException -> HttpStatusCode.Conflict
                else -> HttpStatusCode.InternalServerError
            }
            respondText(error.message ?: "Failed to open the REPL", status = status)
        },
    )
}

private suspend fun ApplicationCall.respondJson(json: String) = respondText(json, ContentType.Application.Json)
