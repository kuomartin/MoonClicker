package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ReplResult
import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ReplChunk
import com.xaxaxax.moonclicker.script.ReplInfo
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import moonclicker.workbench.ReplChunkEvent
import moonclicker.workbench.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeReplRunner : ReplRunner {
    override val state = MutableStateFlow(ReplState())
    override val logLines = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val chunks = MutableSharedFlow<ReplChunk>(replay = 8)
    override val sharedData: Flow<Map<String, Any>> = flowOf(emptyMap())
    var interrupted = false
    var result = ReplResult(ReplStatus.OK, "2")

    override suspend fun open(displayId: Int, scriptId: String?): Result<ReplInfo> {
        if (scriptId == "missing") return Result.failure(IllegalArgumentException("Script not found: missing"))
        return Result.success(ReplInfo(displayId, scriptId).also { state.value = ReplState(it) })
    }

    override suspend fun reset(): Result<ReplInfo> = Result.success(state.value.info!!)

    override suspend fun close() {
        state.value = ReplState()
    }

    override suspend fun eval(code: String, client: String): ReplResult =
        result.also { chunks.emit(ReplChunk(client, code, it)) }

    override fun interrupt() {
        interrupted = true
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
            assertTrue(opened.bodyAsText().contains("\"displayId\":3"))

            val evaluated = client.post("/repl/eval") { setBody("""{"code":"1 + 1","client":"vscode"}""") }
            assertEquals(HttpStatusCode.OK, evaluated.status)
            assertEquals("""{"status":"ok","output":"2"}""", evaluated.bodyAsText())
        }
    }

    @Test
    fun `eval without an open REPL is a conflict`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            val response = client.post("/repl/eval") { setBody("""{"code":"x"}""") }

            assertEquals(HttpStatusCode.Conflict, response.status)
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
    fun `status and interrupt`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }

            assertEquals("""{"open":false,"busy":false}""", client.get("/repl").bodyAsText())
            client.post("/repl/interrupt")
            assertTrue(repl.interrupted)
        }
    }

    @Test
    fun `chunks are streamed to every client`() = runTest {
        repl.state.value = ReplState(ReplInfo(0, null))
        repl.result = ReplResult(ReplStatus.ERROR, "boom")
        testApplication {
            application { workbenchModule(temp.root, NoScripts, NoScripts, repl = repl) }
            val client = createClient { install(ClientWebSockets) }

            client.post("/repl/eval") { setBody("""{"code":"error('boom')","client":"mcp"}""") }

            client.webSocket("/?protocol=$WORKBENCH_PROTOCOL_VERSION") {
                var chunk: ReplChunkEvent? = null
                while (chunk == null) {
                    val frame = incoming.receive()
                    if (frame is Frame.Binary) chunk = StreamEvent.ADAPTER.decode(frame.data).repl?.chunk
                }
                assertEquals("mcp", chunk.client)
                assertEquals("error('boom')", chunk.code)
                assertEquals(ReplChunkEvent.Status.ERROR, chunk.status)
                assertEquals("boom", chunk.output)
            }
        }
    }
}
