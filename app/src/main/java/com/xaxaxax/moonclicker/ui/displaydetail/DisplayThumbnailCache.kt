package com.xaxaxax.moonclicker.ui.displaydetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val THUMBNAIL_MAX_LONG_EDGE = 400

/**
 * Displays 列表卡片用的靜態縮圖快取，vd#41——只在使用者退出 fullscreen 時擷取一次，不做
 * 定期輪詢。in-memory 之外另外落地成小檔案，讓 app 進程重啟後（VD 若還活著）縮圖不會全部
 * 消失；檔案獨立於 `scriptDir`（見 [FullscreenDisplayActivity] 的 intent extra），
 * 不能沿用那套路徑邏輯。[get] 只在 cache miss 時才讀檔解碼，呼叫端（[DisplaysViewModel]）
 * 本來就在背景 dispatcher 上呼叫，不需要在建構時就搶先掃整個目錄。
 */
@Singleton
class DisplayThumbnailCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val thumbnailDir = File(context.filesDir, "display_thumbnails").apply { mkdirs() }
    private val cache = ConcurrentHashMap<Int, ImageBitmap>()

    /**
     * [put] 完成的通知，讓 [DisplaysViewModel][com.xaxaxax.moonclicker.ui.displays.DisplaysViewModel]
     * 不必依賴自己的 `ON_RESUME` 時序去讀縮圖——`FullscreenDisplayActivity` 的擷取現在走
     * `PixelCopy`，是非同步的，常常晚於 Displays 頁面的 resume。
     */
    private val _updates = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val updates: SharedFlow<Int> = _updates.asSharedFlow()

    fun get(displayId: Int): ImageBitmap? {
        cache[displayId]?.let { return it }
        val file = File(thumbnailDir, "$displayId.png")
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }
            .onFailure { Timber.e(it, "Failed to load cached thumbnail for display $displayId") }
            .getOrNull()
            ?.asImageBitmap()
            ?.also { cache[displayId] = it }
    }

    fun put(displayId: Int, rawBitmap: Bitmap) {
        // distributor 已經把 v 轉正（ADR-0017），rawBitmap 就是邏輯空間，直接縮小即可。
        val (targetWidth, targetHeight) =
            computeThumbnailTargetSize(rawBitmap.width, rawBitmap.height, THUMBNAIL_MAX_LONG_EDGE)
        val thumbnail = Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
        cache[displayId] = thumbnail.asImageBitmap()
        runCatching {
            FileOutputStream(File(thumbnailDir, "$displayId.png")).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }.onFailure { Timber.e(it, "Failed to persist thumbnail for display $displayId") }
        _updates.tryEmit(displayId)
    }

    fun remove(displayId: Int) {
        cache.remove(displayId)
        File(thumbnailDir, "$displayId.png").delete()
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
