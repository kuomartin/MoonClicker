package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ReplResult
import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.script.ReplChunk
import com.xaxaxax.moonclicker.script.ReplInfo
import com.xaxaxax.moonclicker.script.ReplLogLine
import com.xaxaxax.moonclicker.script.ReplState
import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptLogLine
import com.xaxaxax.moonclicker.script.ScriptStartOutcome
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class McpFakeRepl : ReplRunner {
    override val state = MutableStateFlow<List<ReplState>>(emptyList())
    override val logLines = MutableSharedFlow<ReplLogLine>(extraBufferCapacity = 8)
    override val chunks = MutableSharedFlow<ReplChunk>(extraBufferCapacity = 8)
    override val sharedData: Flow<Map<String, Map<String, Any>>> = flowOf(emptyMap())
    val opened = mutableListOf<Int>()
    val evaluated = mutableListOf<Pair<String, String>>()
    private var nextId = 1

    override suspend fun open(displayId: Int, scriptId: String?): Result<ReplInfo> {
        opened += displayId
        val info = ReplInfo((nextId++).toString(), displayId, scriptId)
        state.value = state.value + ReplState(info)
        return Result.success(info)
    }

    override suspend fun reset(id: String): Result<ReplInfo>? = state.value.find { it.info.id == id }?.let { Result.success(it.info) }

    override suspend fun close(id: String): Boolean {
        val before = state.value
        state.value = before.filterNot { it.info.id == id }
        return before != state.value
    }

    override suspend fun eval(id: String, code: String, client: String): ReplResult {
        evaluated += id to client
        logLines.emit(ReplLogLine(id, "hello"))
        return if (code == "error()") ReplResult(ReplStatus.ERROR, "boom") else ReplResult(ReplStatus.OK, "2")
    }

    override fun interrupt(id: String) = true
}

private class McpFakeDisplays : DisplaySource {
    val touches = mutableListOf<Triple<Int, List<Int>, Long>>()
    var tree = "[]"

    override suspend fun getDisplays() = listOf(WorkbenchDisplaySummary(0, "Built-in", 1080, 2400, isVirtual = false))

    override suspend fun touch(displayId: Int, points: IntArray, durationMs: Long): Result<Unit> {
        touches += Triple(displayId, points.toList(), durationMs)
        return Result.success(Unit)
    }

    override suspend fun viewTree(displayId: Int): Result<String> =
        if (displayId == 9) Result.failure(IllegalStateException("view tree requires Android 11 (API 30)")) else Result.success(tree)
}

private object McpNoScripts : ScriptRunner, ScriptStream {
    override fun isRunning(scriptId: String) = false
    override suspend fun start(script: Script) = ScriptStartOutcome.Started
    override suspend fun startOnDisplay(script: Script, displayId: Int) = ScriptStartOutcome.Started
    override suspend fun stop(scriptId: String?) = Unit
    override val logLines: Flow<ScriptLogLine> = emptyFlow()
    override val sharedData: Flow<Map<String, Map<String, Any>>> = flowOf(emptyMap())
    override val runningScriptIds: Flow<List<String>> = flowOf(emptyList())
}

class WorkbenchMcpRoutesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val repl = McpFakeRepl()
    private val displays = McpFakeDisplays()

    private fun ApplicationTestBuilder.setUp() =
        application { workbenchModule(temp.root, McpNoScripts, McpNoScripts, displays, repl = repl, appVersion = "0.5.0") }

    private suspend fun ApplicationTestBuilder.rpc(method: String, params: String = "{}"): JsonObject {
        val response = client.post("/mcp") {
            setBody("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.callTool(name: String, args: String = "{}"): JsonObject =
        rpc("tools/call", """{"name":"$name","arguments":$args}""")["result"]!!.jsonObject

    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"

    @Test
    fun `initialize echoes a supported protocol version`() = runTest {
        testApplication {
            setUp()
            val result = rpc("initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")["result"]!!.jsonObject
            assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
            assertEquals("0.5.0", result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)

            val unknown = rpc("initialize", """{"protocolVersion":"1999-01-01"}""")["result"]!!.jsonObject
            assertEquals(MCP_PROTOCOL_VERSIONS.first(), unknown["protocolVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `notifications are accepted without a body and GET is not allowed`() = runTest {
        testApplication {
            setUp()
            val response = client.post("/mcp") { setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp").status)
        }
    }

    @Test
    fun `unknown methods and tools are JSON-RPC errors`() = runTest {
        testApplication {
            setUp()
            assertEquals(-32601, rpc("resources/list")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
            val unknownTool = rpc("tools/call", """{"name":"nope","arguments":{}}""")
            assertEquals(-32602, unknownTool["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `tools list has every tool`() = runTest {
        testApplication {
            setUp()
            val names = rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(
                listOf(
                    "list_displays", "screenshot", "view_tree", "tap", "swipe", "repl_eval", "repl_reset",
                    "list_scripts", "start_script", "stop_script", "script_logs",
                ),
                names,
            )
        }
    }

    @Test
    fun `tap and swipe send logical points`() = runTest {
        testApplication {
            setUp()
            callTool("tap", """{"displayId":2,"x":10,"y":20}""")
            callTool("swipe", """{"displayId":2,"x1":1,"y1":2,"x2":3,"y2":4,"durationMs":500}""")
            assertEquals(
                listOf(Triple(2, listOf(10, 20, 10, 20), 50L), Triple(2, listOf(1, 2, 3, 4), 500L)),
                displays.touches,
            )
            assertTrue(callTool("tap", """{"displayId":2}""").isError())
        }
    }

    @Test
    fun `repl_eval opens one REPL per display and returns logs with the result`() = runTest {
        testApplication {
            setUp()
            val first = callTool("repl_eval", """{"code":"1 + 1"}""")
            assertEquals("hello\n2", first.text())
            callTool("repl_eval", """{"code":"x = 1"}""")
            assertEquals(listOf(0), repl.opened)
            assertEquals(listOf("1" to "mcp", "1" to "mcp"), repl.evaluated)

            assertTrue(callTool("repl_eval", """{"code":"error()"}""").isError())

            // 別人把它關掉之後重開一個。
            repl.close("1")
            callTool("repl_eval", """{"code":"1"}""")
            assertEquals(listOf(0, 0), repl.opened)
        }
    }

    @Test
    fun `view_tree is compact and failures are tool errors`() = runTest {
        displays.tree = """[{"type":"application","package":"com.example","root":
            {"class":"android.widget.FrameLayout","cx":540,"cy":1200,"visible":true,"clickable":false,"children":[
                {"class":"android.widget.Button","text":"OK","id":"com.example:id/ok","cx":540,"cy":1300,"visible":true,"clickable":true,"enabled":true,"children":[]},
                {"class":"android.widget.TextView","text":"hidden","cx":0,"cy":0,"visible":false,"children":[]}
            ]}}]"""
        testApplication {
            setUp()
            assertEquals("[application] com.example\n  Button \"OK\" id=ok @540,1300 clickable\n", callTool("view_tree", """{"displayId":0}""").text())
            val failed = callTool("view_tree", """{"displayId":9}""")
            assertTrue(failed.isError())
            assertEquals("view tree requires Android 11 (API 30)", failed.text())
        }
    }
}
