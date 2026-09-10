package com.xaxaxax.relc.ui.displaydetail

/**
 * 虛擬顯示的哪一塊、以什麼方向，投影到 view 的哪個矩形。
 *
 * 呈現側與觸控側讀**同一個 instance** —— 幾何只有一份，兩邊不可能不一致。
 *
 * 純資料，**不得依賴 `android.graphics`**：那會讓 `app/src/test` 的呼叫擲出
 * `Method ... not mocked`（專案未設 `unitTests.isReturnDefaultValues`、未引入 Robolectric）。
 */
data class Viewport(
    /** 內容矩形在 view 座標中的左上角。 */
    val contentLeft: Float,
    val contentTop: Float,
    /** 內容矩形的大小，等比 fit 後置中，其餘為 letterbox 黑邊。 */
    val contentWidth: Float,
    val contentHeight: Float,
    /** 邏輯座標 → view 座標的縮放比。 */
    val scale: Float,
    /** 邏輯顯示相對於自然方向轉了幾個直角（`Surface.ROTATION_*`，0..3）。 */
    val quarterTurns: Int,
) {
    /** 沒有可畫的內容 —— 虛擬顯示尺寸未知，或 view 尚未 measure。 */
    val isEmpty: Boolean get() = contentWidth <= 0f || contentHeight <= 0f

    /** 要套用到 SurfaceView 的旋轉角度，用於把 surface 空間裡躺著的內容扶正。 */
    val viewRotationDegrees: Float get() = -(quarterTurns * 90f)

    /**
     * SurfaceView **未旋轉時**的佈局尺寸。旋轉 90/270 後外接矩形長寬互換，
     * 因此這裡要先互換，套上 [viewRotationDegrees] 之後才剛好蓋住內容矩形。
     */
    val surfaceViewWidth: Float get() = if (isQuarterTurned) contentHeight else contentWidth
    val surfaceViewHeight: Float get() = if (isQuarterTurned) contentWidth else contentHeight

    /**
     * view 座標 → 邏輯顯示座標。
     *
     * **映射永不失敗** —— 仿射映射在內容矩形之外一樣有定義，超界會得到超出邏輯範圍
     * （含負值）的座標。容納性是另一個問題，由 [isInside] 回答。這個不對稱是刻意的：
     * 手勢一旦在內容區內開始，拖出邊界仍然有效。
     */
    fun toDisplay(viewX: Float, viewY: Float): DisplayPoint =
        if (scale <= 0f) DisplayPoint(0f, 0f)
        else DisplayPoint((viewX - contentLeft) / scale, (viewY - contentTop) / scale)

    /** 該 view 座標是否落在內容矩形內（而非 letterbox 黑邊）。 */
    fun isInside(viewX: Float, viewY: Float): Boolean =
        !isEmpty &&
            viewX >= contentLeft && viewX <= contentLeft + contentWidth &&
            viewY >= contentTop && viewY <= contentTop + contentHeight

    private val isQuarterTurned: Boolean get() = quarterTurns == 1 || quarterTurns == 3
}

/** 虛擬顯示**邏輯空間**中的一點，即 `injectMotionEvent` 使用的座標系。 */
data class DisplayPoint(val x: Float, val y: Float)

/**
 * @param surfaceWidth 虛擬顯示**建立時**的尺寸，不隨旋轉改變（`Display.getMode()`）。
 * @param rotation `Surface.ROTATION_*` 語意：邏輯顯示相對於自然方向的旋轉。
 */
fun viewportOf(
    surfaceWidth: Int,
    surfaceHeight: Int,
    rotation: Int,
    viewWidth: Int,
    viewHeight: Int,
): Viewport {
    // 旋轉 90/270 時邏輯顯示的長寬互換，但 surface 尺寸不變 —— 內容是被旋轉「進」那個
    // 固定尺寸的 surface（真機實測，見地圖 #9 前提 8）。
    val quarterTurns = rotation and 3
    val swapped = quarterTurns == 1 || quarterTurns == 3
    val logicalWidth = (if (swapped) surfaceHeight else surfaceWidth).toFloat()
    val logicalHeight = (if (swapped) surfaceWidth else surfaceHeight).toFloat()

    if (logicalWidth <= 0f || logicalHeight <= 0f || viewWidth <= 0 || viewHeight <= 0) {
        return EMPTY_VIEWPORT
    }

    val scale = minOf(viewWidth / logicalWidth, viewHeight / logicalHeight)
    val width = logicalWidth * scale
    val height = logicalHeight * scale

    return Viewport(
        contentLeft = (viewWidth - width) / 2f,
        contentTop = (viewHeight - height) / 2f,
        contentWidth = width,
        contentHeight = height,
        scale = scale,
        quarterTurns = quarterTurns,
    )
}

private val EMPTY_VIEWPORT = Viewport(
    contentLeft = 0f,
    contentTop = 0f,
    contentWidth = 0f,
    contentHeight = 0f,
    scale = 0f,
    quarterTurns = 0,
)
