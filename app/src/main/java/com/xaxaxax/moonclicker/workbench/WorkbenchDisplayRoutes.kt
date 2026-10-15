package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.streaming.H264EncoderSink
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Display 清單、鏡像切換、即時鏡像串流，委派給 [DisplaySource] 與 [ShizukuManager]。 */
fun Route.displayRoutes(displaySource: DisplaySource, shizukuManager: ShizukuManager?) {
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

    // 鏡像畫面上的點擊與拖曳。座標以畫面比例（0..1）送來，換算到顯示器目前的邏輯空間在服務端做，
    // 鏡像的影格尺寸與邏輯尺寸不必一致。
    post("/displays/{id}/input") {
        val id = call.parameters["id"]?.toIntOrNull()
        val gesture = runCatching { Json.decodeFromString<MirrorGesture>(call.receiveText()) }.getOrNull()
        if (id == null || gesture == null || !gesture.isValid) {
            call.respondText("Invalid input request", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (displaySource.inject(id, gesture)) {
            call.respondText("OK")
        } else {
            call.respondText("Failed to inject input", status = HttpStatusCode.ServiceUnavailable)
        }
    }

    // Realtime mirror (H.264) via WebSocket
    webSocket("/mirror/h264/{displayId}") {
        val displayId = call.parameters["displayId"]?.toIntOrNull()
        if (displayId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown displayId"))
            return@webSocket
        }

        if (shizukuManager == null) {
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Service not connected"))
            return@webSocket
        }

        // 串流期間持有租約：鏡像實體螢幕時服務端沒有 VD，閒置停止會把串流中的服務收掉。
        shizukuManager.leases.hold {
            val service = shizukuManager.withService { it }.getOrNull()
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
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "H.264 encoder failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }
}

/**
 * `POST /displays/{id}/input` 的 body：一個點是 tap（按住 [durationMs]），多個點是沿路徑的 swipe。
 * [points] 是 `[x, y]` 的清單，以鏡像畫面的比例表示（0..1，左上為原點）。
 */
@Serializable
data class MirrorGesture(val points: List<List<Double>>, val durationMs: Long) {
    val isValid: Boolean
        get() = points.isNotEmpty() && points.size <= MAX_POINTS && durationMs in 1..MAX_DURATION_MS &&
            points.all { it.size == 2 && it.all { v -> v in 0.0..1.0 } }

    /** 攤平成 multiTouchSwipe 要的 [x1, y1, x2, y2, ...]；tap 補成同一點的兩次。 */
    fun toLogical(width: Int, height: Int): IntArray {
        val pixels = points.flatMap { (x, y) ->
            listOf((x * width).toInt().coerceIn(0, width - 1), (y * height).toInt().coerceIn(0, height - 1))
        }
        return (if (points.size == 1) pixels + pixels else pixels).toIntArray()
    }

    companion object {
        const val MAX_POINTS = 256
        const val MAX_DURATION_MS = 10_000L
    }
}
