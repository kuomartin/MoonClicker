package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.ScriptEngine
import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptSession
import com.xaxaxax.moonclicker.script.ScriptTarget
import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.script.TemplateRoi
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
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
    /**
     * 停止目前執行中的腳本並等到真的停了才回傳（若沒有在跑，是安全的 no-op）——見
     * [ScriptSession.stopAndAwait]：不等的話，webview 端「stop 完馬上 start」的重啟流程
     * 會因為 native 端還沒把 STOPPED 事件非同步推回來，被 isRunning() 擋下 409。
     */
    suspend fun stop()
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
        override suspend fun stop() = scriptSession.stopAndAwait()
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

/**
 * `templateCount`／`modifiedMs` 故意不給預設值：兩者的合法值都包含 0，kotlinx.serialization
 * 預設 `encodeDefaults = false` 會把等於預設值的欄位整個從 JSON 省略掉，讓 VS Code 端收到
 * `undefined` 而不是 `0`——不給預設值就永遠會序列化出來，不用另外configure 一份 Json。
 */
@Serializable
data class WorkbenchScriptSummary(
    val id: String,
    val name: String,
    /** [TemplateStore.list] 的大小——VS Code 端「編寫」清單顯示用，不用另外拉一次模板清單。 */
    val templateCount: Int,
    /** `main.lua` 與 `templates.json`（若存在）兩者 mtime 取大——粗略但夠用的「上次修改」。 */
    val modifiedMs: Long,
)

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

        displayRoutes(displaySource, shizukuManager)
        scriptRoutes(scriptsRoot, scriptRunner, fileChanges)
        visionRoutes(scriptsRoot, scriptRunner, fileChanges)
    }
}
