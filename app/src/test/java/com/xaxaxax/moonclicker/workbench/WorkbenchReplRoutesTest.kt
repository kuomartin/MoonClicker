package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ReplResult
import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ReplChunk
import com.xaxaxax.moonclicker.script.ReplInfo
import com.xaxaxax.moonclicker.script.ReplLimitException
import com.xaxaxax.moonclicker.script.ReplLogLine
import com.xaxaxax.moonclicker.script.ReplState
import com.xaxaxax.moonclicker.script.ScriptLogLine
import com.xaxaxax.moonclicker.script.ScriptStartOutcome
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import moonclicker.workbench.ReplChunkEvent
import moonclicker.workbench.ReplEvent
import moonclicker.workbench.ReplListEvent
import moonclicker.workbench.ReplStateEvent
import moonclicker.workbench.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeReplRunner : ReplRunner {
    override val state = MutableStateFlow<List<ReplState>>(emptyList())
    override val logLines = MutableSharedFlow<ReplLogLine>(extraBufferCapacity = 8)
    override val chunks = MutableSharedFlow<ReplChunk>(replay = 8)
    override val sharedData: Flow<Map<String, Map<String, Any>>> = flowOf(emptyMap())
    val interrupted = mutableListOf<String>()
    var result = ReplResult(ReplStatus.OK, "2")
    private var nextId = 1

    override suspend fun open(displayId: Int, scriptId: String?): Result<ReplInfo> {
        if (scriptId == "missing") return Result.failure(IllegalArgumentException("Script not found: missing"))
        if (state.value.size >= 2) return Result.failure(ReplLimitException())
        val info = ReplInfo((nextId++).toString(), displayId, scriptId)
        state.value = state.value + ReplState(info)
        return Result.success(info)
    }

    override suspend fun reset(id: String): Result<ReplInfo>? =
        state.value.find { it.info.id == id }?.let { Result.success(it.info) }

    override suspend fun close(id: String): Boolean {
        val before = state.value
        state.value = before.filterNot { it.info.id == id }
        return before.size != state.value.size
    }

    override suspend fun eval(id: String, code: String, client: String): ReplResult =
        result.also { chunks.emit(ReplChunk(id, client, code, it)) }

    override fun interrupt(id: String): Boolean {
        if (state.value.none { it.info.id == id }) return false
        interrupted += id
        return true
    }
}

private object NoScripts : ScriptRunner, ScriptStream {
    override fun isRunning(scriptId: String) = false
    override suspend fun start(script: Script) = ScriptStartOutcome.Started
    override suspend fun startOnDisplay(script: Script, displayId: Int) = ScriptStartOutcome.Started
    override suspend fun stop(scriptId: String?) = Unit
    override val logLines: Flow<ScriptLogLine> = emptyFlow()
    override val sharedData: Flow<Map<String, Map<String, Any>>> = flowOf(emptyMap())
    override val runningScriptIds: Flow<List<String>> = flowOf(emptyList())
}

class WorkbenchReplRoutesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val repl = FakeReplRunner()

    @Test
    fun `open then eval returns the result`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            val opened = client.post("/repl/open") { setBody("""{"displayId":3}""") }
            assertEquals(HttpStatusCode.OK, opened.status)
            assertEquals("""{"id":"1","displayId":3,"busy":false}""", opened.bodyAsText())

            val evaluated = client.post("/repl/1/eval") { setBody("""{"code":"1 + 1","client":"vscode"}""") }
            assertEquals(HttpStatusCode.OK, evaluated.status)
            assertEquals("""{"status":"ok","output":"2"}""", evaluated.bodyAsText())
        }
    }

    @Test
    fun `each open is a separate REPL and closing one leaves the other`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            client.post("/repl/open") { setBody("""{"displayId":0}""") }
            client.post("/repl/open") { setBody("""{"displayId":2,"scriptId":"demo"}""") }
            assertEquals(
                """[{"id":"1","displayId":0,"busy":false},{"id":"2","displayId":2,"scriptId":"demo","busy":false}]""",
                client.get("/repl").bodyAsText(),
            )

            assertEquals(HttpStatusCode.OK, client.post("/repl/1/close").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/repl/1").status)
            assertEquals(HttpStatusCode.OK, client.get("/repl/2").status)
        }
    }

    @Test
    fun `opening past the limit is a conflict`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            repeat(2) { client.post("/repl/open") { setBody("""{"displayId":0}""") } }
            val response = client.post("/repl/open") { setBody("""{"displayId":0}""") }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `an unknown REPL is not found`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            assertEquals(HttpStatusCode.NotFound, client.post("/repl/9/eval") { setBody("""{"code":"x"}""") }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/repl/9/interrupt").status)
            assertEquals(HttpStatusCode.NotFound, client.post("/repl/9/reset").status)
            assertEquals(HttpStatusCode.NotFound, client.post("/repl/9/close").status)
        }
    }

    @Test
    fun `open with an unknown script is not found`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            val response = client.post("/repl/open") { setBody("""{"displayId":0,"scriptId":"missing"}""") }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `interrupt reaches only the named REPL`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            assertEquals("[]", client.get("/repl").bodyAsText())
            repeat(2) { client.post("/repl/open") { setBody("""{"displayId":0}""") } }
            client.post("/repl/2/interrupt")
            assertEquals(listOf("2"), repl.interrupted)
        }
    }

    @Test
    fun `chunks are streamed to every client with their REPL id`() = runTest {
        repl.state.value = listOf(ReplState(ReplInfo("1", 0, null)))
        repl.result = ReplResult(ReplStatus.ERROR, "boom")
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }
            val client = createClient { install(ClientWebSockets) }

            client.post("/repl/1/eval") { setBody("""{"code":"error('boom')","client":"mcp"}""") }

            client.webSocket("/?protocol=$WORKBENCH_PROTOCOL_VERSION") {
                var event: ReplEvent? = null
                while (event?.chunk == null) {
                    val frame = incoming.receive()
                    if (frame is Frame.Binary) event = StreamEvent.ADAPTER.decode(frame.data).repl
                }
                val chunk = event!!.chunk!!
                assertEquals("1", event.repl_id)
                assertEquals("mcp", chunk.client)
                assertEquals("error('boom')", chunk.code)
                assertEquals(ReplChunkEvent.Status.ERROR, chunk.status)
                assertEquals("boom", chunk.output)
            }
        }
    }

    @Test
    fun `the stream starts with the list of open REPLs`() = runTest {
        repl.state.value = listOf(ReplState(ReplInfo("1", 2, "demo"), busy = true))
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/?protocol=$WORKBENCH_PROTOCOL_VERSION") {
                var list: ReplListEvent? = null
                while (list == null) {
                    val frame = incoming.receive()
                    if (frame is Frame.Binary) list = StreamEvent.ADAPTER.decode(frame.data).repl?.list
                }
                assertEquals(listOf(ReplStateEvent(id = "1", display_id = 2, script_id = "demo", busy = true)), list.repls)
            }
        }
    }
}
