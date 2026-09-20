package com.xaxaxax.moonclicker.ui.displaydetail

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 裁切工作流的唯一權威：擷取到的 bitmap、拉出的框、letterbox 用的 [Viewport]、存檔，
 * 全部收在這一個 interface 後面，[FullscreenDisplayActivity] 不需要自己再拼一次。
 *
 * 不是 ViewModel——只需要 [Context] 算存檔路徑，[FullscreenDisplayViewModel] 直接 `new` 出來，
 * 不用 Hilt 注入它本身。
 */
class CropSession(private val context: Context) {

    data class CropUiState(
        val isActive: Boolean = false,
        val bitmap: Bitmap? = null,
        val cropRect: CropRect? = null,
        val activeHandle: DragHandle = DragHandle.None,
        val viewport: Viewport = viewportOf(0, 0, 0, 0, 0),
    ) {
        val canSave: Boolean get() = cropRect?.normalized()?.let { it.width > 0 && it.height > 0 } == true
    }

    private val _state = MutableStateFlow(CropUiState())
    val state: StateFlow<CropUiState> = _state.asStateFlow()

    /** 開始裁切；此時還沒有 bitmap，擷取是非同步的（見 [setCapturedBitmap]）。 */
    fun start() {
        _state.value = CropUiState(isActive = true)
    }

    fun setCapturedBitmap(bitmap: Bitmap) {
        _state.value = _state.value.copy(bitmap = bitmap)
    }

    fun cancel() {
        _state.value = CropUiState(isActive = false)
    }

    /**
     * Canvas 量到的像素尺寸——letterbox 用同一套 [Viewport]，因為擷取到的 bitmap 轉正後
     * （v=90/270°）長寬會互換，硬拉伸塞滿畫布會讓畫面變形。
     */
    fun onCanvasMeasured(width: Int, height: Int) {
        val bitmap = _state.value.bitmap ?: return
        _state.value = _state.value.copy(
            viewport = viewportOf(
                surfaceWidth = bitmap.width,
                surfaceHeight = bitmap.height,
                d = 0,
                viewWidth = width,
                viewHeight = height,
            )
        )
    }

    fun onDragStart(x: Float, y: Float, handleRadius: Float) {
        val current = _state.value
        val handle = hitTest(current.cropRect, x, y, handleRadius)
        _state.value = if (handle == DragHandle.None) {
            current.copy(cropRect = CropRect(x, y, x, y), activeHandle = DragHandle.BottomRight)
        } else {
            current.copy(activeHandle = handle)
        }
    }

    fun onDrag(dx: Float, dy: Float) {
        val current = _state.value
        val rect = current.cropRect ?: return
        _state.value = current.copy(cropRect = dragResize(rect, current.activeHandle, dx, dy))
    }

    fun onDragEnd() {
        val current = _state.value
        _state.value = current.copy(cropRect = current.cropRect?.normalized(), activeHandle = DragHandle.None)
    }

    /** 換算目前的框到 bitmap 像素、裁切、存檔；成功後回到 [cancel] 之後的狀態。 */
    fun confirmSave(scriptDir: String, name: String): Boolean {
        val current = _state.value
        val bitmap = current.bitmap ?: return false
        val rect = current.cropRect?.normalized() ?: return false
        val saved = saveCropped(bitmap, cropRectToBitmapRect(rect, current.viewport), scriptDir, name)
        if (saved) cancel()
        return saved
    }

    private fun saveCropped(bitmap: Bitmap, cropRect: PixelRect, scriptDir: String, name: String): Boolean {
        try {
            val left = cropRect.left.coerceAtLeast(0)
            val top = cropRect.top.coerceAtLeast(0)
            val right = cropRect.right.coerceAtMost(bitmap.width)
            val bottom = cropRect.bottom.coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return false

            val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
            val templateDir = if (scriptDir.isBlank()) {
                File(context.getExternalFilesDir(null), "images")
            } else {
                File(scriptDir)
            }
            if (!templateDir.exists()) templateDir.mkdirs()

            val file = File(templateDir, "$name.png")
            FileOutputStream(file).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save cropped image")
            return false
        }
    }
}

/**
 * 純資料，不得依賴 `android.graphics`——理由同 [Viewport]：`android.graphics.Rect` 在
 * `app/src/test` 下不會丟 `not mocked`，而是靜默把所有欄位回傳 0，比丟例外更容易騙過測試。
 */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** [CropRect]（view 座標）→ bitmap 像素座標，透過 [viewport]。抽成純函式供測試直接釘住換算本身。 */
internal fun cropRectToBitmapRect(rect: CropRect, viewport: Viewport): PixelRect {
    val topLeft = viewport.toDisplay(rect.left, rect.top)
    val bottomRight = viewport.toDisplay(rect.right, rect.bottom)
    return PixelRect(
        topLeft.x.roundToInt(), topLeft.y.roundToInt(),
        bottomRight.x.roundToInt(), bottomRight.y.roundToInt(),
    )
}
