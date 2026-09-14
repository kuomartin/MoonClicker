package com.xaxaxax.relc.workbench

import com.xaxaxax.relc.script.Script
import com.xaxaxax.relc.script.ScriptArchive
import com.xaxaxax.relc.script.ScriptSession
import com.xaxaxax.relc.script.ScriptStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Script Workbench 的內嵌 HTTP server。開關與生命週期由 [com.xaxaxax.relc.workbench.WorkbenchService]
 * 這個獨立的、使用者手動控制的前景服務承載，與腳本執行狀態無關。
 *
 * 監聽所有網卡（不只 loopback）是刻意的：VS Code 端在開發者的電腦上，透過同一個區網連進來，
 * milestone 1 沒有配對驗證（見 #53 Out of Scope），這是已知、記錄在案的風險，不是這裡能修的漏洞。
 */
/**
 * 觸發執行需要的最小介面（見 #61）。真正實作直接轉呼叫 [ScriptSession]；存在這一層是為了讓
 * `workbenchModule` 比照 [ScriptStore.scan] 的可測試模式，JVM 測試能餵假的執行器，不需要
 * 真的 `ScriptSession`/Hilt。
 */
interface ScriptRunner {
    fun isRunning(): Boolean
    fun start(script: Script)
}

@Singleton
class WorkbenchServer @Inject constructor(
    private val scriptStore: ScriptStore,
    private val scriptSession: ScriptSession,
) {
    private val scriptRunner = object : ScriptRunner {
        override fun isRunning() = scriptSession.state.value.isRunning
        override fun start(script: Script) = scriptSession.start(script)
    }

    private var server: EmbeddedServer<*, *>? = null

    private val _address = MutableStateFlow<String?>(null)

    /** 目前監聽的 "ip:port"，供 QR code 配對顯示；server 未啟動時是 null。 */
    val address: StateFlow<String?> = _address.asStateFlow()

    /** @return 是否成功啟動——bind 失敗（例如 port 被佔用）時回傳 false，不讓例外往外拋。 */
    fun start(): Boolean {
        if (server != null) return true
        return try {
            // 不指定 host 會讓底層 bind 成 dual-stack IPv6 wildcard（已在真機上驗證：LISTEN
            // 位址是全零的 ::，不是 0.0.0.0）。Android 的 epoll-based Selector 對「剛 accept、
            // 來源是 IPv4-mapped-IPv6 位址」的 channel 會漏掉第一次 OP_READ 就緒事件，導致
            // 連線停在 ESTABLISHED 卻永遠讀不到資料——loopback 走純 IPv4 不會踩到，跨裝置走
            // WiFi 才會。直接 bind 裝置在區網上的實際 IPv4 位址，繞開 wildcard 的雙棧歧義。
            val host = localIpv4Address()
            val scriptsRoot = scriptStore.root
            server = embeddedServer(
                CIO,
                port = PORT,
                host = host,
                module = { workbenchModule(scriptsRoot, scriptRunner) },
            ).start(wait = false)
            _address.value = "$host:$PORT"
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

/**
 * [scriptsRoot] 拆成參數而不是內部自己算，是為了比照 [ScriptStore.scan] 的作法，讓
 * `WorkbenchServerTest` 能直接餵一個暫存目錄進來，不需要 Hilt 或真的 Android Context。
 */
fun Application.workbenchModule(scriptsRoot: File, scriptRunner: ScriptRunner) {
    install(WebSockets)
    routing {
        get("/health") {
            call.respondText("OK")
        }
        // Milestone 1 只驗證「連線建立/斷開本身」（見 #57），業務路由（執行/log 串流）
        // 是後續票的範圍——先用 echo 讓 extension 端能驗證連線確實是雙向可用的 WebSocket。
        webSocket("/") {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    send(Frame.Text(frame.readText()))
                }
            }
        }

        // Script Folder 雙向同步（見 #58）。三個路由都直接吃/還純 zip bytes，不用
        // multipart——一次同步就是一份完整的資料夾內容，沒有欄位要拆。
        get("/scripts") {
            val scripts = ScriptStore.scan(scriptsRoot).map { WorkbenchScriptSummary(it.id, it.name) }
            call.respondText(Json.encodeToString(scripts), ContentType.Application.Json)
        }

        // Pull：把裝置上的 Script Folder 打包成 zip 讓 extension 端下載展開到本機專案。
        get("/scripts/{id}/export") {
            val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
            if (script == null) {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val bytes = ByteArrayOutputStream().also { ScriptArchive.export(script, it) }.toByteArray()
            call.respondBytes(bytes, ContentType.Application.Zip)
        }

        // Push：extension 端把本機編輯完的 zip 推回來，整份覆蓋掉裝置上同 id 的資料夾——
        // milestone 1 是單向覆蓋語意，不做多人衝突解決（見 #53 Out of Scope）。
        put("/scripts/{id}/import") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respondText("Missing script id", status = HttpStatusCode.BadRequest)
                return@put
            }
            when (val result = ScriptArchive.replace(call.receiveStream(), scriptsRoot, id)) {
                is ScriptArchive.ImportResult.Imported -> call.respondText("OK")
                is ScriptArchive.ImportResult.Failed ->
                    call.respondText(result.reason, status = HttpStatusCode.BadRequest)
            }
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
    }
}
