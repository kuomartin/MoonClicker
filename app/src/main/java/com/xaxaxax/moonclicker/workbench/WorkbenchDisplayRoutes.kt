package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.engine.streaming.H264EncoderSink
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Display 清單／鏡像切換／即時鏡像串流，委派給 [DisplaySource] 與 [ShizukuManager]。 */
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
