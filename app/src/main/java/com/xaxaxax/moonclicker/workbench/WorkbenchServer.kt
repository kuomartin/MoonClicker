package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ScriptEngine
import com.xaxaxax.moonclicker.script.SafePath
import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptSession
import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.script.ScriptTarget
import com.xaxaxax.moonclicker.script.TemplateRoi
import com.xaxaxax.moonclicker.script.TemplateStore
import com.xaxaxax.moonclicker.engine.streaming.H264EncoderSink
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.CloseReason
import io.ktor.websocket.readText
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moonclicker.workbench.FileChangeEvent
import moonclicker.workbench.StreamEvent
import timber.log.Timber

/**
 * Script Workbench 的內嵌 HTTP server。開關與生命週期由 [com.xaxaxax.moonclicker.workbench.WorkbenchService]
 * 這個獨立的、使用者手動控制的前景服務承載，與腳本執行狀態無關。
 *
 * 監聽所有網卡（不只 loopback）是刻意的：VS Code 端在開發者的電腦上，透過同一個區網連進來，
 * 以 PIN 配對換發 token（見 [WorkbenchAuthStore]），除 `/pair`、`/health` 外所有路由皆強制驗證。
 */
/**
 * 觸發執行需要的最小介面（見 #61）。真正實作直接轉呼叫 [ScriptSession]；存在這一層是為了讓
 * `workbenchModule` 比照 [ScriptStore.scan] 的可測試模式，JVM 測試能餵假的執行器，不需要
 * 真的 `ScriptSession`/Hilt。
 */
interface ScriptRunner {
    fun isRunning(): Boolean
    fun start(script: Script)
    /** 停止目前執行中的腳本（若沒有在跑，是安全的 no-op）。 */
    fun stop()
    /** 跟 [start] 不同：不吃 `script.json` 的 `display`，強制跑在既有的虛擬顯示 [displayId] 上（見 vision-test）。 */
    fun startOnDisplay(script: Script, displayId: Int)
}

/**
 * log/data 即時串流需要的最小介面（見 #62）。真正實作直接轉呼叫全域單例
 * [ScriptEngine]；存在這一層是為了讓 `workbenchModule` 的 JVM 測試餵假的
 * flow，不用真的跑一次原生引擎才能發出 log/data 事件。
 */
interface ScriptStream {
    val logLines: Flow<String>
    val sharedData: StateFlow<Map<String, Any>>
}

/**
 * VS Code FileSystemProvider 的 `onDidChangeFile` 要推播的單一檔案變動。只涵蓋透過 HTTP
 * 單檔案 API 寫入的改動（self-write）——外部（檔案管理員、USB 接電腦）直接動到 Script
 * Folder 不會被偵測到：inotify／`FileObserver` 不遞迴，要涵蓋外部改動得對每個腳本資料夾
 * （與其子目錄）各自維護一個 watch，決定不做這件事（見 vscode-fsprovider-plan.md E3）。
 */
enum class ScriptFileChangeKind { CREATED, CHANGED, DELETED }

data class ScriptFileChange(val scriptId: String, val path: String, val kind: ScriptFileChangeKind)

/**
 * Display info needed by the extension to show mirror choices (見 #87).
 */
@Serializable
data class WorkbenchDisplaySummary(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isVirtual: Boolean,
    val isMirrorActive: Boolean = true,
)

interface DisplaySource {
    fun getDisplays(): List<WorkbenchDisplaySummary>?
    fun toggleMirror(displayId: Int, enable: Boolean): Boolean = false
}

@Singleton
class WorkbenchServer @Inject constructor(
    private val scriptStore: ScriptStore,
    private val scriptSession: ScriptSession,
    private val shizukuManager: ShizukuManager,
    val authStore: WorkbenchAuthStore,
) {
    private val scriptRunner = object : ScriptRunner {
        override fun isRunning() = scriptSession.state.value.isRunning
        override fun start(script: Script) = scriptSession.start(script)
        override fun stop() = scriptSession.stop()
        override fun startOnDisplay(script: Script, displayId: Int) =
            scriptSession.start(script, ScriptTarget.ExistingVirtual(displayId))
    }

    private val scriptStream = object : ScriptStream {
        override val logLines: Flow<String> get() = ScriptEngine.logLines
        override val sharedData: StateFlow<Map<String, Any>> get() = ScriptEngine.sharedData
    }

    private val displaySource = object : DisplaySource {
        override fun getDisplays(): List<WorkbenchDisplaySummary>? {
            val service = shizukuManager.service ?: return null
            val displayInfos = runCatching { service.displayInfos.toList() }.getOrNull()
            if (displayInfos != null) {
                return displayInfos.map { info ->
                    WorkbenchDisplaySummary(
                        id = info.displayId,
                        name = info.name ?: if (info.isPhysical) "Physical Display" else "Virtual Display ${info.displayId}",
                        width = info.width,
                        height = info.height,
                        isVirtual = !info.isPhysical,
                        isMirrorActive = if (info.isPhysical) info.isMirrorActive else true
                    )
                }
            }
            val displayIds = mutableListOf(0)
            displayIds.addAll(service.virtualDisplays.toList())
            return displayIds.mapNotNull { id ->
                val size = service.getDisplaySize(id) ?: return@mapNotNull null
                WorkbenchDisplaySummary(
                    id = id,
                    name = if (id == 0) "Physical Display" else "Virtual Display $id",
                    width = size[0],
                    height = size[1],
                    isVirtual = id != 0,
                    isMirrorActive = if (id == 0) runCatching { service.isDisplayMirrorActive(0) }.getOrDefault(false) else true
                )
            }
        }

        override fun toggleMirror(displayId: Int, enable: Boolean): Boolean {
            val service = shizukuManager.service ?: return false
            return runCatching {
                if (enable) service.acquireDisplayMirror(displayId)
                else service.releaseDisplayMirror(displayId)
            }.getOrDefault(false)
        }
    }

    private var server: EmbeddedServer<*, *>? = null

    private val _address = MutableStateFlow<String?>(null)

    /** 目前監聽的 "ip:port"，供 QR code 配對顯示；server 未啟動時是 null。 */
    val address: StateFlow<String?> = _address.asStateFlow()

    /** @return 是否成功啟動——bind 失敗（例如 port 被佔用）時回傳 false，不讓例外往外拋。 */
    fun start(): Boolean {
        if (server != null) return true
        return try {
            // 為了支援 Android 模擬器透過 adb forward 連線 (來源 IP 為 127.0.0.1)，
            // 這裡改為綁定 0.0.0.0 監聽所有網卡。
            // 注意：如果在真機 WiFi 環境遇到連線卡 ESTABLISHED 讀不到資料的問題，
            // 可能是 Ktor 的 0.0.0.0 觸發了 dual-stack wildcard bug。
            val host = "0.0.0.0"
            val scriptsRoot = scriptStore.root
            server = embeddedServer(
                CIO,
                port = PORT,
                host = host,
                module = { workbenchModule(scriptsRoot, scriptRunner, scriptStream, displaySource, shizukuManager, authStore) },
            ).start(wait = false)
            _address.value = "${localIpv4Address()}:$PORT" // UI 顯示仍保留實際區網 IP 供參考
            true
        } catch (t: Throwable) {
            Timber.e(t, "WorkbenchServer failed to bind port $PORT")
            false
        }
    }

    /**
     * 重新計算目前的區網 IPv4 位址（例如切換 WiFi 網路後）並更新 [address]；server 沒在跑
     * 就不做事——避免在還沒 start() 之前把 address 誤設成一個其實沒人在監聽的位址。
     */
    fun refreshAddress() {
        if (server == null) return
        _address.value = "${localIpv4Address()}:$PORT"
    }

    /**
     * 找裝置在區網（WiFi）上的實際 IPv4 位址；找不到就退回 loopback——不能退回 "0.0.0.0"，
     * 那個字串本來就是預設值，退回它等於繞了一圈又回到會觸發 dual-stack wildcard 那個 bug
     * 的起點（已在真機上驗證：退回 wildcard 時連 loopback 都連不上，不只是跨裝置連不上）。
     */
    internal fun localIpv4Address(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?: "127.0.0.1"

    fun stop() {
        server?.stop()
        server = null
        _address.value = null
    }

    companion object {
        const val PORT = 8787
    }
}

@Serializable
data class WorkbenchScriptSummary(val id: String, val name: String)

@Serializable
data class ScriptTreeEntry(
    val path: String,
    val size: Long,
    val mtimeMs: Long,
    val isDirectory: Boolean,
    val sha256: String,
)

@Serializable
data class RenameRequest(val from: String, val to: String, val overwrite: Boolean = false)

@Serializable
data class PairRequest(val pin: String = "")

/** `POST /scripts/{id}/vision-test` 的 request body，見 vision-test 合約。 */
@Serializable
data class VisionTestRequest(
    val displayId: Int,
    val image: String,
    val roi: TemplateRoi,
    val threshold: Double,
    val intervalMs: Long = 300,
)

@Serializable
data class PairResponse(val token: String)

/**
 * [scriptsRoot] 拆成參數而不是內部自己算，是為了比照 [ScriptStore.scan] 的作法，讓
 * `WorkbenchServerTest` 能直接餵一個暫存目錄進來，不需要 Hilt 或真的 Android Context。
 */
fun Application.workbenchModule(
    scriptsRoot: File,
    scriptRunner: ScriptRunner,
    scriptStream: ScriptStream,
    displaySource: DisplaySource = object : DisplaySource {
        override fun getDisplays(): List<WorkbenchDisplaySummary> = emptyList()
    },
    shizukuManager: ShizukuManager? = null,
    authStore: WorkbenchAuthStore? = null,
) {
    install(WebSockets)
    // 誰透過 HTTP 單檔案 API 寫入，都要推給每個開著的 WebSocket——一個 VS Code client
    // 存檔，另一個開著同一顆腳本的 client 也要看到。
    val fileChanges = MutableSharedFlow<ScriptFileChange>(extraBufferCapacity = 64)
    install(CORS) {
        anyHost()
    }

    if (authStore != null) {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path != "/pair" && path != "/health") {
                val authHeader = call.request.headers["Authorization"]
                val tokenFromHeader = if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    authHeader.removePrefix("Bearer ").trim()
                } else {
                    authHeader
                }
                val tokenFromQuery = call.request.queryParameters["token"]
                val token = tokenFromHeader ?: tokenFromQuery

                if (!authStore.isValidToken(token)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                    finish()
                }
            }
        }
    }

    routing {
        get("/health") {
            call.respondText("OK")
        }

        post("/pair") {
            if (authStore == null) {
                call.respondText("Pairing unsupported", status = HttpStatusCode.NotImplemented)
                return@post
            }
            val pin = runCatching { call.receiveText() }.getOrNull()?.let { body ->
                runCatching { Json.decodeFromString<PairRequest>(body).pin }.getOrNull()
            } ?: call.request.queryParameters["pin"] ?: ""

            when (val result = authStore.pairWithPin(pin)) {
                is PairResult.Success -> {
                    call.respondText(
                        Json.encodeToString(PairResponse(result.token)),
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
                is PairResult.InvalidPin -> {
                    call.respondText("Invalid PIN", status = HttpStatusCode.Unauthorized)
                }
                is PairResult.LockedOut -> {
                    call.respondText(
                        "Too many failed attempts. Try again in ${result.retryAfterSeconds} seconds.",
                        status = HttpStatusCode.TooManyRequests
                    )
                }
                is PairResult.PairingModeInactive -> {
                    call.respondText("Pairing mode is not active", status = HttpStatusCode.Forbidden)
                }
            }
        }

        get("/displays") {
            val displays = displaySource.getDisplays()
            if (displays == null) {
                call.respondText("Service not connected", status = HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respondText(Json.encodeToString(displays), ContentType.Application.Json)
        }

        post("/displays/{id}/mirror") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respondText("Invalid display id", status = HttpStatusCode.BadRequest)
                return@post
            }
            val enable = call.request.queryParameters["enable"]?.toBooleanStrictOrNull() ?: true
            val success = displaySource.toggleMirror(id, enable)
            if (success) {
                call.respondText(if (enable) "Mirror acquired" else "Mirror released")
            } else {
                call.respondText("Failed to update mirror", status = HttpStatusCode.InternalServerError)
            }
        }

        // log + data.set 即時串流（見 #62），外加保留 #57 用來驗證連線本身的 echo。
        //
        // data 用 sharedData 這個 StateFlow 的訂閱語意天生就有「重連重送目前快照」的效果——
        // 新的 collector 一訂閱就會先拿到目前值，不需要另外記錄「上次送過什麼」。
        // log 是 logLines 這個 SharedFlow，live-only，斷線期間錯過的行不補、不維護歷史 buffer。
        webSocket("/") {
            val dataJob = launch {
                scriptStream.sharedData.collect { send(dataFrame(it)) }
            }
            val logJob = launch {
                scriptStream.logLines.collect { send(logFrame(it)) }
            }
            val fileChangeJob = launch {
                fileChanges.collect { send(fileChangeFrame(it)) }
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        send(Frame.Text(frame.readText()))
                    }
                }
            } finally {
                dataJob.cancel()
                logJob.cancel()
                fileChangeJob.cancel()
            }
        }

        get("/scripts") {
            val scripts = ScriptStore.scan(scriptsRoot).map { WorkbenchScriptSummary(it.id, it.name) }
            call.respondText(Json.encodeToString(scripts), ContentType.Application.Json)
        }

        // Script Folder 單檔案讀寫（見 vscode-local-mirror-plan）：VS Code 端維護一份本機
        // 鏡像資料夾，靠這幾個路由跟裝置做背景同步——取代整包 zip 的 export/import，
        // 也取代直接把這份 API 接成 vscode.FileSystemProvider 的做法（LuaLS 讀不到
        // virtual scheme，見 vscode-fsprovider-plan E2）。
        get("/scripts/{id}/tree") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val entries = script.dir.walkTopDown()
                .filter { it != script.dir }
                .map { file ->
                    ScriptTreeEntry(
                        path = file.relativeTo(script.dir).invariantSeparatorsPath,
                        size = if (file.isFile) file.length() else 0L,
                        mtimeMs = file.lastModified(),
                        isDirectory = file.isDirectory,
                        // VS Code 端拿這個判斷 self-echo（自己剛推上去的改動不用重新下載）
                        // 跟要不要跳過某個檔案的 pull，見 vscode-local-mirror-plan Q2。
                        sha256 = if (file.isFile) file.sha256Hex() else "",
                    )
                }
                .toList()
            call.respondText(Json.encodeToString(entries), ContentType.Application.Json)
        }

        get("/scripts/{id}/files/{path...}") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val relativePath = call.filePathParam()
            val target = SafePath.resolve(script.dir, relativePath)
            if (target == null || !target.isFile) {
                call.respondText("File not found", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondBytes(target.readBytes())
        }

        put("/scripts/{id}/files/{path...}") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@put
            }
            val relativePath = call.filePathParam()
            val target = SafePath.resolve(script.dir, relativePath)
            if (target == null || relativePath.isBlank()) {
                call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
                return@put
            }
            if (target.isDirectory) {
                call.respondText("Path is a directory", status = HttpStatusCode.Conflict)
                return@put
            }
            val existed = target.isFile
            target.parentFile?.mkdirs()
            target.writeBytes(call.receiveStream().readBytes())
            fileChanges.tryEmit(
                ScriptFileChange(script.id, relativePath, if (existed) ScriptFileChangeKind.CHANGED else ScriptFileChangeKind.CREATED)
            )
            call.respondText("OK")
        }

        delete("/scripts/{id}/files/{path...}") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@delete
            }
            val relativePath = call.filePathParam()
            val target = SafePath.resolve(script.dir, relativePath)
            if (target == null || relativePath.isBlank() || !target.exists()) {
                call.respondText("File not found", status = HttpStatusCode.NotFound)
                return@delete
            }
            if (!target.deleteRecursively()) {
                call.respondText("Failed to delete", status = HttpStatusCode.InternalServerError)
                return@delete
            }
            fileChanges.tryEmit(ScriptFileChange(script.id, relativePath, ScriptFileChangeKind.DELETED))
            call.respondText("OK")
        }

        post("/scripts/{id}/mkdir/{path...}") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@post
            }
            val relativePath = call.filePathParam()
            val target = SafePath.resolve(script.dir, relativePath)
            if (target == null || relativePath.isBlank()) {
                call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
                return@post
            }
            if (target.isFile) {
                call.respondText("Path already exists as a file", status = HttpStatusCode.Conflict)
                return@post
            }
            val existed = target.isDirectory
            target.mkdirs()
            if (!existed) {
                fileChanges.tryEmit(ScriptFileChange(script.id, relativePath, ScriptFileChangeKind.CREATED))
            }
            call.respondText("OK")
        }

        // body 是 JSON {"from": "...", "to": "...", "overwrite": false}——跟 mkdir/files 不同，
        // 來源與目的地都是欄位，不適合塞進路徑本身。
        post("/scripts/{id}/rename") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@post
            }
            val body = runCatching { Json.decodeFromString<RenameRequest>(call.receiveText()) }.getOrNull()
            if (body == null || body.from.isBlank() || body.to.isBlank()) {
                call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
                return@post
            }
            val source = SafePath.resolve(script.dir, body.from)
            val destination = SafePath.resolve(script.dir, body.to)
            if (source == null || destination == null || !source.exists()) {
                call.respondText("Invalid rename request", status = HttpStatusCode.BadRequest)
                return@post
            }
            if (destination.exists() && !body.overwrite) {
                call.respondText("Destination already exists", status = HttpStatusCode.Conflict)
                return@post
            }
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.deleteRecursively()
            if (!source.renameTo(destination)) {
                call.respondText("Failed to rename", status = HttpStatusCode.InternalServerError)
                return@post
            }
            fileChanges.tryEmit(ScriptFileChange(script.id, body.from, ScriptFileChangeKind.DELETED))
            fileChanges.tryEmit(ScriptFileChange(script.id, body.to, ScriptFileChangeKind.CREATED))
            call.respondText("OK")
        }

        // 觸發執行（見 #61）：統一經過既有 ScriptSession，不建立第二條執行路徑——
        // 透過 VS Code 觸發跟透過 App UI 觸發，都會是同一個 ScriptSession.start()。
        post("/scripts/{id}/run") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@post
            }
            if (scriptRunner.isRunning()) {
                call.respondText("A script is already running", status = HttpStatusCode.Conflict)
                return@post
            }
            scriptRunner.start(script)
            call.respondText("Started", status = HttpStatusCode.Accepted)
        }

        // 停止目前執行中的腳本（見 vision-test 合約）：一般腳本、vision-test 都吃同一個
        // ScriptRunner slot，這裡不分是哪一種，統一停。沒有腳本在跑時呼叫也不報錯——
        // ScriptSession.stop() 本來就是安全的 no-op。
        post("/run/stop") {
            scriptRunner.stop()
            call.respondText("Stopped")
        }

        // 拿既有模板去對虛擬顯示做一次性 vision.find 迴圈（見 vision-test 合約）：{id} 只用來
        // 解出模板圖片的絕對路徑，實際執行的目標顯示器是 body 裡的 displayId，不是這份腳本的
        // script.json——所以不能走 scriptRunner.start(script)，得用 startOnDisplay。
        post("/scripts/{id}/vision-test") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@post
            }
            val request = runCatching { Json.decodeFromString<VisionTestRequest>(call.receiveText()) }.getOrNull()
            if (request == null || request.image.isBlank()) {
                call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
                return@post
            }
            if (scriptRunner.isRunning()) {
                call.respondText("A script is already running", status = HttpStatusCode.Conflict)
                return@post
            }
            val imagePath = File(script.dir, request.image).absolutePath
            val syntheticScript = VisionTestScript.materialize(
                scriptsRoot = scriptsRoot,
                imagePath = imagePath,
                roi = request.roi,
                threshold = request.threshold,
                intervalMs = request.intervalMs,
            )
            scriptRunner.startOnDisplay(syntheticScript, request.displayId)
            call.respondText("Started", status = HttpStatusCode.Accepted)
        }

        // 裁切模板存回裝置（見 #75）：body 是裁切完的 PNG bytes，roi（邏輯座標，依 ADR-0013）
        // 走 query string，因為 body 已經是純圖片 bytes、不留給欄位混進去的空間。
        // 已存在同名模板回 409（見 #72 story 8）；script 目錄不存在或 templates.json 現有
        // 內容解析失敗回 4xx，不靜默失敗（見 #72 story 9）。
        put("/scripts/{id}/templates/{name}") {
            val id = call.parameters["id"]
            val name = call.parameters["name"]
            if (id.isNullOrBlank() || name.isNullOrBlank() || name.contains('/') || name.contains("..")) {
                call.respondText("Invalid script id or template name", status = HttpStatusCode.BadRequest)
                return@put
            }
            val script = ScriptStore.scan(scriptsRoot).find { it.id == id }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@put
            }
            val query = call.request.queryParameters
            val roi = listOf("x", "y", "w", "h").map { query[it]?.toIntOrNull() }
                .let { (x, y, w, h) -> if (x != null && y != null && w != null && h != null) TemplateRoi(x, y, w, h) else null }
            if (roi == null) {
                call.respondText(
                    "Missing or invalid roi (expects integer x, y, w, h query params)",
                    status = HttpStatusCode.BadRequest,
                )
                return@put
            }
            val pngBytes = call.receiveStream().readBytes()
            when (val result = TemplateStore.write(script.dir, name, roi, pngBytes)) {
                is TemplateStore.WriteResult.Written -> {
                    // 這條路徑不是走新的單檔案 API（見上面的 /files/*path），裁切工具直接
                    // 呼叫這裡寫檔，寫完得自己補一次廣播，VS Code 的本機鏡像才會知道要重抓
                    // 這兩個檔案——不補的話裁切完的模板不會同步回去（見 #103 之後的實測回報）。
                    fileChanges.tryEmit(
                        ScriptFileChange(id, result.imageFile.relativeTo(script.dir).invariantSeparatorsPath, ScriptFileChangeKind.CREATED)
                    )
                    fileChanges.tryEmit(
                        ScriptFileChange(id, result.metaFile.relativeTo(script.dir).invariantSeparatorsPath, ScriptFileChangeKind.CHANGED)
                    )
                    call.respondText("OK")
                }
                is TemplateStore.WriteResult.Conflict ->
                    call.respondText("Template already exists: ${result.name}", status = HttpStatusCode.Conflict)
                is TemplateStore.WriteResult.Failed ->
                    call.respondText(result.reason, status = HttpStatusCode.BadRequest)
            }
        }

        // Realtime mirror (H.264) via WebSocket
        webSocket("/mirror/h264/{displayId}") {
            val displayId = call.parameters["displayId"]?.toIntOrNull()
            if (displayId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown displayId"))
                return@webSocket
            }

            val service = shizukuManager?.service
            if (service == null) {
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Service not connected"))
                return@webSocket
            }

            val size = service.getDisplaySurfaceSize(displayId)
            if (size == null || size[0] == 0) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid displayId"))
                return@webSocket
            }

            val sink = H264EncoderSink(service, displayId, size[0], size[1])
            try {
                sink.h264Flow.collect { nalu ->
                    send(Frame.Binary(true, nalu))
                }
            } catch (e: Exception) {
                Timber.e(e, "H264 WebSocket error")
            }
        }
    }
}

/**
 * `StreamEvent{log=...}` 編碼成二進位 proto frame——形狀來自 `proto/workbench_stream_event.proto`，
 * 跟 extension 端的 `@bufbuild/protobuf` 生成型別是同一份 schema，不用兩邊手動同步解析。
 */
private fun logFrame(line: String): Frame =
    Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(log = line)))

/**
 * 每次變動送整個 map，不算 diff。`data` 的值理應只有 Double/String/Boolean，但
 * `ScriptEngine.sharedData` 型別是 `Map<String, Any>`，這個假設不是型別系統保證的——
 * Wire 的 Struct 編碼只接受 null/Boolean/Double/String/List/Map，其餘一律
 * `IllegalArgumentException`，未知型別因此在送出前先轉成字串，不讓一個不符預期的值
 * 讓整個 WebSocket session 崩潰。
 */
private fun dataFrame(data: Map<String, Any>): Frame =
    Frame.Binary(true, StreamEvent.ADAPTER.encode(StreamEvent(data_ = data.mapValues { (_, value) -> value.toStructValue() })))

private fun Any.toStructValue(): Any = when (this) {
    is Double, is Boolean, is String -> this
    else -> toString()
}

private fun File.sha256Hex(): String {
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

private fun fileChangeFrame(change: ScriptFileChange): Frame =
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

/** `{path...}` 這種 tail wildcard 路由參數，Ktor 拆成多個 segment，這裡合併回一段相對路徑。 */
private fun io.ktor.server.application.ApplicationCall.filePathParam(): String =
    parameters.getAll("path")?.joinToString("/").orEmpty()
