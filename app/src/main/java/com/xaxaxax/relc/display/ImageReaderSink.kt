package com.xaxaxax.relc.display

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

class ImageReaderSink(
    private val width: Int,
    private val height: Int,
) : DisplaySink {

    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val latestBitmap = AtomicReference<Bitmap?>(null)

    /** Buffer for direct pixel copy to avoid allocations */
    private var copyImageRow: IntArray? = null

    override fun acquireSurface(): Surface? {
        if (imageReader == null) {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            copyImageRow = IntArray(width)

            handlerThread = HandlerThread("ImageReaderSink").apply { start() }
            handler = Handler(handlerThread!!.looper)

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val bitmap = processImage(image)
                    val old = latestBitmap.getAndSet(bitmap)
                    Log.v(TAG, "New frame acquired, size: ${bitmap.width}x${bitmap.height}")
                    // In a real app, we might want to return 'old' to a pool
                } finally {
                    image.close()
                }
            }, handler)
        }
        return imageReader?.surface
    }

    override fun start() {}

    override fun stop() {}

    override fun release() {
        imageReader?.close()
        imageReader = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        latestBitmap.set(null)
        copyImageRow = null
    }

    fun getLatestBitmap(): Bitmap? = latestBitmap.get()

    private fun processImage(image: Image): Bitmap {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)

        // Optimized copy logic from Klick'r
        if (!image.haveRowPadding() && image.width == bitmap.width && image.height == bitmap.height) {
            if (image.directPixelsCopyTo(bitmap)) return bitmap
        }

        image.pixelsCopyTo(bitmap)
        return bitmap
    }

    private fun Image.haveRowPadding(): Boolean =
        planes[0].rowStride != (width * planes[0].pixelStride)

    private fun Image.directPixelsCopyTo(bitmap: Bitmap): Boolean =
        try {
            bitmap.copyPixelsFromBuffer(planes[0].buffer.asReadOnlyBuffer().rewind())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to direct copy pixels from buffer", e)
            false
        }

    private fun Image.pixelsCopyTo(bitmap: Bitmap) {
        val imageRow = copyImageRow ?: return
        val srcByteBuffer = planes[0].buffer.asIntBuffer()
        val pixelStride = planes[0].pixelStride / 4 // Int size

        for (y in 0 until height) {
            srcByteBuffer.position((y * planes[0].rowStride) / 4)
            srcByteBuffer.get(imageRow)

            // ABGR -> ARGB (swap R & B)
            for (i in imageRow.indices) {
                val pixel = imageRow[i]
                imageRow[i] = (pixel and 0xFF00FF00.toInt()) or
                        ((pixel and 0x00FF0000) ushr 16) or
                        ((pixel and 0x000000FF) shl 16)
            }
            bitmap.setPixels(imageRow, 0, width, 0, y, width, 1)
        }
    }

    companion object {
        private const val TAG = "ImageReaderSink"
    }
}
