package com.xaxaxax.relc.workbench

import com.xaxaxax.relc.engine.ScriptEngine
import com.xaxaxax.relc.script.Script
import com.xaxaxax.relc.script.ScriptArchive
import com.xaxaxax.relc.script.ScriptSession
import com.xaxaxax.relc.script.ScriptStore
import com.xaxaxax.relc.script.TemplateRoi
import com.xaxaxax.relc.script.TemplateStore
import com.xaxaxax.relc.engine.streaming.H264EncoderSink
import com.xaxaxax.relc.shizuku.ShizukuManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.CloseReason
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import relc.workbench.StreamEvent
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
 * Realtime mirror 串流需要的最小介面（見 #73）。正式實作是
 * [com.xaxaxax.relc.ui.displaydetail.MirrorFrameSource]，接 [com.xaxaxax.relc.ui.displaydetail.VirtualDisplayMirror]
 * 那個 `TextureView`；存在這一層是為了讓 `workbenchModule` 的 JVM 測試餵假的 frame flow，
 * 不需要真的 `Display`/`Surface`/`TextureView`。
 */
interface FrameSource {
    /**
     * @return [displayId] 的 frame flow；displayId 未知時回傳 null，route 依此回 404。
     * 回傳的 flow 是「活的鏡像串流」，正常情況下不會自己結束——收集者斷線時，route 寫入
     * 失敗會讓 collect 拋出並結束，flow 本身的 finally/cancellation 語意就地完成清理，
     * 呼叫端不需要另外追蹤一份 job。
     */
    fun frames(displayId: Int): Flow<ByteArray>?
}

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
    private val frameSource: FrameSource,
    private val shizukuManager: ShizukuManager,
) {
    private val scriptRunner = object : ScriptRunner {
        override fun isRunning() = scriptSession.state.value.isRunning
        override fun start(script: Script) = scriptSession.start(script)
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
                module = { workbenchModule(scriptsRoot, scriptRunner, scriptStream, frameSource, displaySource, shizukuManager) },
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

/**
 * [scriptsRoot] 拆成參數而不是內部自己算，是為了比照 [ScriptStore.scan] 的作法，讓
 * `WorkbenchServerTest` 能直接餵一個暫存目錄進來，不需要 Hilt 或真的 Android Context。
 */
fun Application.workbenchModule(
    scriptsRoot: File,
    scriptRunner: ScriptRunner,
    scriptStream: ScriptStream,
    frameSource: FrameSource,
    displaySource: DisplaySource = object : DisplaySource {
        override fun getDisplays(): List<WorkbenchDisplaySummary> = emptyList()
    },
    shizukuManager: ShizukuManager? = null,
) {
    install(WebSockets)
    install(CORS) {
        anyHost()
    }
    routing {
        get("/health") {
            call.respondText("OK")
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
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        send(Frame.Text(frame.readText()))
                    }
                }
            } finally {
                dataJob.cancel()
                logJob.cancel()
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
                is TemplateStore.WriteResult.Written -> call.respondText("OK")
                is TemplateStore.WriteResult.Conflict ->
                    call.respondText("Template already exists: ${result.name}", status = HttpStatusCode.Conflict)
                is TemplateStore.WriteResult.Failed ->
                    call.respondText(result.reason, status = HttpStatusCode.BadRequest)
            }
        }

        // Realtime mirror（見 #73、#76）。選 multipart/x-mixed-replace（MJPEG）而不是逐張輪詢
        // 或 WebRTC 是 #72/#52 已經定案的決策，這條路由跟 webSocket("/") 是分開的傳輸，互不
        // 干擾。404 涵蓋兩種情況：displayId 不是數字，以及那台顯示目前沒有活著的擷取來源
        // （鏡像畫面沒開，見 MirrorFrameSource）。
        get("/mirror/{displayId}") {
            val displayId = call.parameters["displayId"]?.toIntOrNull()
            val frames = displayId?.let(frameSource::frames)
            if (frames == null) {
                call.respondText("Unknown displayId", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=$MIRROR_BOUNDARY")) {
                // frames 正常不會自己完成；寫入失敗（client 斷線）讓 collect 拋出並結束，
                // 不需要另外 launch 一個 job 來追蹤——這個 suspend lambda 本身就是要被清理的
                // coroutine，收集動作直接發生在它裡面。
                frames.collect { frame ->
                    writeStringUtf8("--$MIRROR_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n")
                    writeFully(frame)
                    writeStringUtf8("\r\n")
                    flush()
                }
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

/** MJPEG multipart 串流的 boundary token，跟內容本身無關，純粹是個不會出現在 JPEG bytes 裡的分隔字串。 */
private const val MIRROR_BOUNDARY = "relc-mirror-frame"

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
