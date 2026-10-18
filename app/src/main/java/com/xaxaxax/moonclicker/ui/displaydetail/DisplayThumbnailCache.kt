package com.xaxaxax.moonclicker.ui.displaydetail

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

internal const val THUMBNAIL_MAX_LONG_EDGE = 400

/**
 * Displays 列表卡片用的縮圖，只放在記憶體。來源有兩個：[DisplaysViewModel] 每次 refresh 從
 * distributor 抓的目前影格，以及退出 fullscreen 時 PixelCopy 的那張（立即更新、不必等 refresh）。
 * 不落地成檔案：displayId 重開機後會從頭編號，檔案會比 VD 活得久，新 VD 拿到舊 id 就顯示別人的畫面；
 * app 進程重啟時 VD 若還活著，下一次 refresh 會重新抓。
 */
@Singleton
class DisplayThumbnailCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val cache = ConcurrentHashMap<Int, ImageBitmap>()

    init {
        // 舊版把縮圖寫在這裡；現在不讀了，清掉免得佔空間。
        File(context.filesDir, "display_thumbnails").deleteRecursively()
    }

    /**
     * [put] 完成的通知，讓 [DisplaysViewModel][com.xaxaxax.moonclicker.ui.displays.DisplaysViewModel]
     * 不必依賴自己的 `ON_RESUME` 時序去讀縮圖——兩個來源都是非同步的，常常晚於 Displays 頁面的 resume。
     */
    private val _updates = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val updates: SharedFlow<Int> = _updates.asSharedFlow()

    fun get(displayId: Int): ImageBitmap? = cache[displayId]

    fun put(displayId: Int, rawBitmap: Bitmap) {
        // distributor 已經把 v 轉正（ADR-0017），rawBitmap 就是邏輯空間，直接縮小即可。
        val (targetWidth, targetHeight) =
            computeThumbnailTargetSize(rawBitmap.width, rawBitmap.height, THUMBNAIL_MAX_LONG_EDGE)
        val thumbnail = Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
        cache[displayId] = thumbnail.asImageBitmap()
        _updates.tryEmit(displayId)
    }

    fun remove(displayId: Int) {
        cache.remove(displayId)
    }
}

/** 縮到長邊最多 [maxLongEdge]、維持長寬比；已經夠小就原樣回傳。 */
internal fun computeThumbnailTargetSize(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return width to height
    val scale = maxLongEdge.toFloat() / longEdge
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}
