package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.script.ScriptStartOutcome
import com.xaxaxax.moonclicker.script.ScriptStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.File
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MCP server（Streamable HTTP，stateless）：`POST /mcp` 收一則 JSON-RPC 訊息，回 `application/json`。
 * 不開 SSE 串流，所以 `GET /mcp` 回 405。驗證沿用 Workbench token（`Authorization: Bearer`）。
 */
fun Route.mcpRoutes(
    scope: CoroutineScope,
    scriptsRoot: File,
    scriptRunner: ScriptRunner,
    scriptStream: ScriptStream,
    displaySource: DisplaySource,
    repl: ReplRunner?,
    serverVersion: String,
) {
    val tools = McpTools(scope, scriptsRoot, scriptRunner, scriptStream, displaySource, repl)

    get("/mcp") {
        call.respondText("SSE stream not supported", status = HttpStatusCode.MethodNotAllowed)
    }

    post("/mcp") {
        val message = runCatching { Json.parseToJsonElement(call.receiveText()) }.getOrNull() as? JsonObject
        if (message == null) {
            call.respondJson(rpcError(JsonNull, PARSE_ERROR, "Expected a single JSON-RPC message"))
            return@post
        }
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        // 沒有 id 的是 notification 或 client 的 response，不用回。
        if (id == null || id is JsonNull || method == null) {
            call.respond(HttpStatusCode.Accepted)
            return@post
        }
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val response = when (method) {
            "initialize" -> rpcResult(id, initializeResult(params, serverVersion))
            "ping" -> rpcResult(id, JsonObject(emptyMap()))
            "tools/list" -> rpcResult(id, buildJsonObject { put("tools", tools.definitions) })
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                if (name == null || !tools.has(name)) {
                    rpcError(id, INVALID_PARAMS, "Unknown tool: $name")
                } else {
                    rpcResult(id, tools.call(name, args))
                }
            }
            else -> rpcError(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
        call.respondJson(response)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(body: JsonElement) =
    respondText(body.toString(), ContentType.Application.Json)

/** 支援的 MCP 協定版本，新的在前。client 要求的版本在清單裡就照用，否則回最新的。 */
internal val MCP_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

private fun initializeResult(params: JsonObject, serverVersion: String) = buildJsonObject {
    val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
    put("protocolVersion", requested?.takeIf { it in MCP_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSIONS.first())
    putJsonObject("capabilities") { putJsonObject("tools") {} }
    putJsonObject("serverInfo") {
        put("name", "moonclicker")
        put("version", serverVersion)
    }
    put("instructions", MCP_INSTRUCTIONS)
}

private val MCP_INSTRUCTIONS = """
    Controls an Android device running MoonClicker. Coordinates are display pixels in the display's logical space; ids come from list_displays (0 is the physical screen).
    Prefer view_tree to locate elements and tap their @x,y centers; use screenshot when the tree lacks the information.
    repl_eval runs Lua with the MoonClicker script API (input, vision, ocr, ui, app, data, log); see https://github.com/kuomartin/MoonClicker/blob/master/docs/lua-api.md.
""".trimIndent()

private fun rpcResult(id: JsonElement, result: JsonElement) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

private const val PARSE_ERROR = -32700
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602

/** 工具的錯誤（參數不對、裝置端失敗）以 `isError` 回給模型，不是 JSON-RPC error。 */
private class ToolError(message: String) : Exception(message)

private class McpTools(
    scope: CoroutineScope,
    private val scriptsRoot: File,
    private val scriptRunner: ScriptRunner,
    scriptStream: ScriptStream,
    private val displaySource: DisplaySource,
    private val repl: ReplRunner?,
) {
    private class Tool(val definition: JsonObject, val run: suspend (JsonObject) -> JsonObject)

    private val tools = LinkedHashMap<String, Tool>()

    /** MCP 自己開的 REPL，每個顯示器一個。被別人關掉時下次 eval 重開。 */
    private val replIds = HashMap<Int, String>()
    private val replLock = Mutex()

    /** 每支腳本最近的 log，從 server 啟動開始收。 */
    private val logs = HashMap<String, ArrayDeque<String>>()

    init {
        scope.launch {
            scriptStream.logLines.collect { line ->
                synchronized(logs) {
                    val buffer = logs.getOrPut(line.scriptId) { ArrayDeque() }
                    buffer.addLast(line.line)
                    while (buffer.size > MAX_LOG_LINES) buffer.removeFirst()
                }
            }
        }

        tool("list_displays", "List displays with their ids and logical sizes.") { _ ->
            val displays = displaySource.getDisplays() ?: throw ToolError("MoonClicker service is not connected")
            text(displays.joinToString("\n") { d ->
                "${d.id}: ${d.name} ${d.width}x${d.height}" +
                    (if (d.isVirtual) " virtual" else "") + (if (d.isMirrorActive) "" else " (mirror off)")
            })
        }

        tool(
            "screenshot",
            "Capture a display as PNG. The image may be scaled down; tap coordinates use display pixels, as the text says.",
            { int("displayId", "Display id from list_displays.", required = true) },
        ) { args ->
            val displayId = args.requireInt("displayId")
            val shot = displaySource.screenshot(displayId, SCREENSHOT_MAX_EDGE).toolResult()
            content(
                textItem(
                    "Display $displayId is ${shot.width}x${shot.height}; image is ${shot.imageWidth}x${shot.imageHeight}" +
                        if (shot.imageWidth != shot.width) " (multiply image coordinates by %.3f).".format(shot.width.toDouble() / shot.imageWidth) else ".",
                ),
                buildJsonObject {
                    put("type", "image")
                    put("data", Base64.getEncoder().encodeToString(shot.png))
                    put("mimeType", "image/png")
                },
            )
        }

        tool(
            "view_tree",
            "Read the accessibility view tree of a display (Android 11+), top-most window first. Each line is one element: class, \"text\", desc, id, @centerX,centerY and state flags.",
            {
                int("displayId", "Display id from list_displays.", required = true)
                bool("raw", "Return the full JSON (every node, bounds and flag) instead of the compact text.")
            },
        ) { args ->
            val json = displaySource.viewTree(args.requireInt("displayId")).toolResult()
            text(if (args.bool("raw") == true) json else compactViewTree(json))
        }

        tool(
            "tap",
            "Tap at a point in display pixels.",
            {
                int("displayId", "Display id from list_displays.", required = true)
                int("x", "X in display pixels.", required = true)
                int("y", "Y in display pixels.", required = true)
                int("durationMs", "How long to hold, default 50. Use 600 or more for a long press.")
            },
        ) { args ->
            val x = args.requireInt("x")
            val y = args.requireInt("y")
            displaySource.touch(args.requireInt("displayId"), intArrayOf(x, y, x, y), args.duration(50)).toolResult()
            text("Tapped ($x, $y)")
        }

        tool(
            "swipe",
            "Swipe in a straight line between two points in display pixels.",
            {
                int("displayId", "Display id from list_displays.", required = true)
                int("x1", "Start X.", required = true)
                int("y1", "Start Y.", required = true)
                int("x2", "End X.", required = true)
                int("y2", "End Y.", required = true)
                int("durationMs", "Duration, default 300.")
            },
        ) { args ->
            val points = intArrayOf(args.requireInt("x1"), args.requireInt("y1"), args.requireInt("x2"), args.requireInt("y2"))
            displaySource.touch(args.requireInt("displayId"), points, args.duration(300)).toolResult()
            text("Swiped (${points[0]}, ${points[1]}) to (${points[2]}, ${points[3]})")
        }

        if (repl != null) {
            tool(
                "repl_eval",
                "Run Lua in a persistent REPL bound to a display, with the MoonClicker script API. Globals persist between calls; locals do not. Returns log() output and the returned values.",
                {
                    string("code", "Lua code.", required = true)
                    int("displayId", "Display the REPL targets for input and vision, default 0.")
                    int("timeoutMs", "Interrupt the code after this long, default 30000.")
                },
            ) { args -> evalInRepl(args) }

            tool(
                "repl_reset",
                "Clear the globals of the REPL bound to a display.",
                { int("displayId", "Display id, default 0.") },
            ) { args ->
                val displayId = args.int("displayId") ?: 0
                val id = replLock.withLock { replIds[displayId] }
                if (id == null || repl.reset(id)?.isSuccess != true) {
                    replLock.withLock { replIds.remove(displayId) }
                }
                text("REPL for display $displayId reset")
            }
        }

        tool("list_scripts", "List scripts on the device and whether they are running.") { _ ->
            val scripts = ScriptStore.scan(scriptsRoot)
            text(
                scripts.joinToString("\n") { s ->
                    "${s.id}: ${s.name}" + if (scriptRunner.isRunning(s.id)) " (running)" else ""
                }.ifEmpty { "No scripts" },
            )
        }

        tool(
            "start_script",
            "Start a script by id. Use script_logs to follow it.",
            { string("scriptId", "Script id from list_scripts.", required = true) },
        ) { args ->
            val scriptId = args.requireString("scriptId")
            val script = ScriptStore.scan(scriptsRoot).find { it.id == scriptId } ?: throw ToolError("Script not found: $scriptId")
            when (val outcome = scriptRunner.start(script)) {
                ScriptStartOutcome.Started -> text("Started $scriptId")
                ScriptStartOutcome.AlreadyRunning -> throw ToolError("$scriptId is already running")
                is ScriptStartOutcome.DisplayBusy -> throw ToolError("Display ${outcome.displayId} is running ${outcome.scriptId}")
                is ScriptStartOutcome.Failed -> throw ToolError(outcome.message)
            }
        }

        tool(
            "stop_script",
            "Stop a script, or every script when scriptId is omitted.",
            { string("scriptId", "Script id; omit to stop all.") },
        ) { args ->
            val scriptId = args.string("scriptId")
            scriptRunner.stop(scriptId)
            text(if (scriptId == null) "Stopped all scripts" else "Stopped $scriptId")
        }

        tool(
            "script_logs",
            "Recent log lines of a script, oldest first, kept since the Workbench started.",
            {
                string("scriptId", "Script id.", required = true)
                int("limit", "Maximum lines, default 100.")
            },
        ) { args ->
            val scriptId = args.requireString("scriptId")
            val limit = (args.int("limit") ?: 100).coerceIn(1, MAX_LOG_LINES)
            val lines = synchronized(logs) { logs[scriptId]?.takeLast(limit).orEmpty() }
            text(lines.joinToString("\n").ifEmpty { "No log lines from $scriptId" })
        }
    }

    val definitions: JsonArray get() = JsonArray(tools.values.map { it.definition })

    fun has(name: String) = name in tools

    suspend fun call(name: String, args: JsonObject): JsonObject =
        try {
            tools.getValue(name).run(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message ?: e.javaClass.simpleName)
        }

    private suspend fun evalInRepl(args: JsonObject): JsonObject = coroutineScope {
        val repl = repl!!
        val code = args.requireString("code")
        val displayId = args.int("displayId") ?: 0
        val timeoutMs = (args.int("timeoutMs") ?: 30_000).toLong().coerceIn(100, 600_000)
        val replId = replLock.withLock {
            replIds[displayId]?.takeIf { id -> repl.state.value.any { it.info.id == id } }
                ?: repl.open(displayId, null).getOrElse { throw ToolError(it.message ?: "Failed to open a REPL") }.id
                    .also { replIds[displayId] = it }
        }
        val logLines = mutableListOf<String>()
        val logJob = launch(start = CoroutineStart.UNDISPATCHED) {
            repl.logLines.filter { it.replId == replId }.collect { synchronized(logLines) { logLines += it.line } }
        }
        val pending = async { repl.eval(replId, code, MCP_CLIENT) }
        val result = withTimeoutOrNull(timeoutMs) { pending.await() } ?: run {
            repl.interrupt(replId)
            pending.await()
        }
        // log 與結果走不同的 flow，給還在路上的 log 一點時間。
        delay(LOG_GRACE_MS)
        logJob.cancel()
        val output = buildString {
            synchronized(logLines) { logLines.forEach { append(it).append('\n') } }
            when (result.status) {
                ReplStatus.OK -> append(result.output)
                ReplStatus.ERROR -> append(result.output)
                ReplStatus.INCOMPLETE -> append("Incomplete code: ").append(result.output)
                ReplStatus.INTERRUPTED -> append("Interrupted (timeout ${timeoutMs}ms)")
                ReplStatus.CLOSED -> append("REPL was closed")
            }
        }.trimEnd().ifEmpty { "(no output)" }
        if (result.status == ReplStatus.OK) text(output) else errorResult(output)
    }

    private fun tool(
        name: String,
        description: String,
        schema: SchemaBuilder.() -> Unit = {},
        run: suspend (JsonObject) -> JsonObject,
    ) {
        val properties = SchemaBuilder().apply(schema)
        val definition = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                put("properties", JsonObject(properties.properties))
                if (properties.required.isNotEmpty()) putJsonArray("required") { properties.required.forEach { add(it) } }
            }
        }
        tools[name] = Tool(definition, run)
    }

    private fun <T> Result<T>.toolResult(): T = getOrElse { throw ToolError(it.message ?: it.javaClass.simpleName) }

    private fun JsonObject.duration(default: Long) = (int("durationMs")?.toLong() ?: default).coerceIn(1, MirrorGesture.MAX_DURATION_MS)

    companion object {
        const val MAX_LOG_LINES = 500
        const val SCREENSHOT_MAX_EDGE = 1280
        const val MCP_CLIENT = "mcp"
        const val LOG_GRACE_MS = 100L
    }
}

private class SchemaBuilder {
    val properties = LinkedHashMap<String, JsonElement>()
    val required = mutableListOf<String>()

    private fun prop(name: String, type: String, description: String, required: Boolean) {
        properties[name] = buildJsonObject {
            put("type", type)
            put("description", description)
        }
        if (required) this.required += name
    }

    fun int(name: String, description: String, required: Boolean = false) = prop(name, "integer", description, required)
    fun string(name: String, description: String, required: Boolean = false) = prop(name, "string", description, required)
    fun bool(name: String, description: String, required: Boolean = false) = prop(name, "boolean", description, required)
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.requireInt(key: String) = int(key) ?: throw ToolError("Missing integer argument: $key")

private fun JsonObject.requireString(key: String) = string(key) ?: throw ToolError("Missing string argument: $key")

private fun textItem(text: String) = buildJsonObject {
    put("type", "text")
    put("text", text)
}

private fun content(vararg items: JsonObject, isError: Boolean = false) = buildJsonObject {
    put("content", JsonArray(items.toList()))
    if (isError) put("isError", true)
}

private fun text(text: String) = content(textItem(text))

private fun errorResult(message: String) = content(textItem(message), isError = true)
