package com.xaxaxax.moonclicker.workbench

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.xaxaxax.moonclicker.DisplaySink
import com.xaxaxax.moonclicker.IMoonClickerService
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** 一張顯示器截圖。[width]/[height] 是顯示器的邏輯尺寸，點擊座標以它為準；[png] 可能縮小過。 */
class DisplayScreenshot(val png: ByteArray, val width: Int, val height: Int, val imageWidth: Int, val imageHeight: Int)

private val screenshotHandler by lazy { Handler(HandlerThread("WorkbenchScreenshot").apply { start() }.looper) }

/**
 * 掛一個 [DisplaySink] 收一張影格。新掛上的 sink 即使畫面靜止也會收到目前這張（見 GlesDistributor）。
 * 實體螢幕沒開鏡像時掛不上，回 failure。長邊超過 [maxEdge] 時等比縮小。
 */
internal suspend fun captureScreenshot(service: IMoonClickerService, displayId: Int, maxEdge: Int): Result<DisplayScreenshot> {
    val surfaceSize = service.getDisplaySurfaceSize(displayId)
    if (surfaceSize == null || surfaceSize.size < 2 || surfaceSize[0] <= 0 || surfaceSize[1] <= 0) {
        return Result.failure(IllegalArgumentException("Display $displayId not found"))
    }
    val logical = service.getDisplaySize(displayId)?.takeIf { it.size >= 2 && it[0] > 0 } ?: surfaceSize
    val (w, h) = surfaceSize[0] to surfaceSize[1]
    val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    val sink = DisplaySink(displayId, reader.surface)
    try {
        val frame = withTimeoutOrNull(FRAME_TIMEOUT_MS) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val bitmap = image.use {
                        val plane = it.planes[0]
                        Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888).apply {
                            copyPixelsFromBuffer(plane.buffer)
                        }
                    }
                    r.setOnImageAvailableListener(null, null)
                    if (cont.isActive) cont.resume(bitmap) else bitmap.recycle()
                }, screenshotHandler)
                if (!sink.attach(service)) cont.resume(null)
            }
        } ?: return Result.failure(
            IllegalStateException("No frame from display $displayId; a physical display needs its mirror turned on"),
        )
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(logical[0], logical[1]))
        val outW = (logical[0] * scale).toInt().coerceAtLeast(1)
        val outH = (logical[1] * scale).toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(frame, 0, 0, w, h)
        val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
        val png = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        listOf(frame, cropped, scaled).distinct().forEach { it.recycle() }
        return Result.success(DisplayScreenshot(png, logical[0], logical[1], outW, outH))
    } finally {
        sink.close()
        reader.close()
    }
}

private const val FRAME_TIMEOUT_MS = 3000L
