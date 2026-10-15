package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ReplResult
import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.script.ReplChunk
import com.xaxaxax.moonclicker.script.ReplInfo
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
    val state: StateFlow<ReplState>
    val logLines: Flow<String>
    val chunks: Flow<ReplChunk>
    val sharedData: Flow<Map<String, Any>>
    suspend fun open(displayId: Int, scriptId: String?): Result<ReplInfo>
    suspend fun reset(): Result<ReplInfo>
    suspend fun close()
    suspend fun eval(code: String, client: String): ReplResult
    fun interrupt()
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

/** `GET /repl` 與 open/reset 的回應。[displayId]、[scriptId] 只在 [open] 時有意義。 */
@Serializable
data class ReplStatusResponse(
    val open: Boolean,
    val displayId: Int? = null,
    val scriptId: String? = null,
    val busy: Boolean,
)

internal fun ReplState.toResponse() =
    ReplStatusResponse(open = info != null, displayId = info?.displayId, scriptId = info?.scriptId, busy = busy)

internal fun ReplStatus.toResponse(): ReplResultStatus = when (this) {
    ReplStatus.OK -> ReplResultStatus.OK
    ReplStatus.ERROR -> ReplResultStatus.ERROR
    ReplStatus.INCOMPLETE -> ReplResultStatus.INCOMPLETE
    ReplStatus.INTERRUPTED -> ReplResultStatus.INTERRUPTED
    ReplStatus.CLOSED -> ReplResultStatus.CLOSED
}

/** REPL 的開關與執行。整個 App 只有一個 REPL，所有連線共用。 */
fun Route.replRoutes(repl: ReplRunner) {
    get("/repl") {
        call.respondJson(Json.encodeToString(repl.state.value.toResponse()))
    }

    post("/repl/open") {
        val body = runCatching { Json.decodeFromString<ReplOpenRequest>(call.receiveText()) }.getOrNull()
        if (body == null) {
            call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        call.respondOpened(repl.open(body.displayId, body.scriptId), repl)
    }

    post("/repl/reset") {
        if (repl.state.value.info == null) {
            call.respondText("REPL is not open", status = HttpStatusCode.Conflict)
            return@post
        }
        call.respondOpened(repl.reset(), repl)
    }

    post("/repl/close") {
        repl.close()
        call.respondText("Closed")
    }

    // 回應等到這段跑完才送出；要中斷就另外送 /repl/interrupt。
    post("/repl/eval") {
        val body = runCatching { Json.decodeFromString<ReplEvalRequest>(call.receiveText()) }.getOrNull()
        if (body == null) {
            call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (repl.state.value.info == null) {
            call.respondText("REPL is not open", status = HttpStatusCode.Conflict)
            return@post
        }
        val result = repl.eval(body.code, body.client)
        call.respondJson(Json.encodeToString(ReplEvalResponse(result.status.toResponse(), result.output)))
    }

    post("/repl/interrupt") {
        repl.interrupt()
        call.respondText("Interrupted")
    }
}

private suspend fun ApplicationCall.respondOpened(result: Result<ReplInfo>, repl: ReplRunner) {
    result.fold(
        onSuccess = { info ->
            respondJson(Json.encodeToString(ReplStatusResponse(true, info.displayId, info.scriptId, repl.state.value.busy)))
        },
        onFailure = { error ->
            val status = if (error is IllegalArgumentException) HttpStatusCode.NotFound else HttpStatusCode.InternalServerError
            respondText(error.message ?: "Failed to open the REPL", status = status)
        },
    )
}

private suspend fun ApplicationCall.respondJson(json: String) = respondText(json, ContentType.Application.Json)
