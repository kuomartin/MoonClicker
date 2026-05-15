package com.xaxaxax.relc.display.cv

import android.graphics.Bitmap
import android.graphics.Rect
import com.xaxaxax.relc.display.ImageReaderSink
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CVManager @Inject constructor() {

    private val nativeDetector = NativeDetector()
    private var sink: ImageReaderSink? = null

    fun init(sink: ImageReaderSink) {
        this.sink = sink
    }

    fun getCurrentFrame(): Bitmap? = sink?.getLatestBitmap()

    /**
     * 在指定範圍內尋找目標圖片 (支援彩色辨識)
     * @param target 小圖
     * @param region 搜尋區域 (null 表示全螢幕)
     * @param threshold 相似度門檻 (0.0 - 1.0)
     * @param maxColorDiff 顏色差異容許度 (0.0 - 100.0, 越小越嚴格, 預設 15.0)
     */
    fun findImage(
        target: Bitmap,
        region: Rect? = null,
        threshold: Double = 0.8,
        maxColorDiff: Double = 15.0
    ): DetectionResult? {
        val currentFrame = sink?.getLatestBitmap() ?: return null

        val searchRegion = region ?: Rect(0, 0, currentFrame.width, currentFrame.height)

        val result = nativeDetector.matchTemplateNative(
            currentFrame,
            target,
            searchRegion.left,
            searchRegion.top,
            searchRegion.width(),
            searchRegion.height()
        )

        // 雙重驗證：相似度要夠高，且顏色差異要夠小
        return if (result != null &&
            result.confidenceRate >= threshold &&
            result.colorDiff <= maxColorDiff) {
            result
        } else {
            null
        }
    }

    fun release() {
        sink = null
    }
}
