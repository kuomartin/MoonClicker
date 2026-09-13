package com.xaxaxax.relc.ui.displaydetail

/**
 * 虛擬顯示的哪一塊、以什麼方向，投影到 view 的哪個矩形。
 *
 * 呈現側與觸控側讀同一個 instance，且座標變換只有 [displayPerViewPixel] 一個來源，
 * 因此兩邊不可能不一致。
 *
 * 純資料，不得依賴 `android.graphics`——那會讓 `app/src/test` 的呼叫擲出
 * `Method ... not mocked`。
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

    /** 邏輯顯示相對於自然方向是否轉了直角（長寬因此互換）。 */
    val isQuarterTurned: Boolean get() = isQuarterTurn(quarterTurns)

    /** 要套用到鏡像 view 的旋轉角度，用於把 surface 空間裡躺著的內容扶正。 */
    val viewRotationDegrees: Float get() = -(quarterTurns * 90f)

    /**
     * 鏡像 view **未旋轉時**的佈局尺寸。旋轉 90/270 後外接矩形長寬互換，
     * 因此這裡要先互換，套上 [viewRotationDegrees] 之後才剛好蓋住內容矩形。
     */
    val unrotatedWidth: Float get() = if (isQuarterTurned) contentHeight else contentWidth
    val unrotatedHeight: Float get() = if (isQuarterTurned) contentWidth else contentHeight

    /**
     * 一個 view 像素對應多少邏輯像素 —— 座標變換的**唯一**來源。
     *
     * [toDisplay] 用它，觸控端組 `Matrix` 也用它，兩者因此不可能算出不同的結果。
     */
    val displayPerViewPixel: Float get() = if (scale <= 0f) 0f else 1f / scale

    /**
     * view 座標 → 邏輯顯示座標。
     *
     * 映射永不失敗：仿射映射在內容矩形之外一樣有定義，超界會得到超出邏輯範圍（含負值）
     * 的座標。容納性由 [isInside] 回答——這個不對稱是刻意的，手勢一旦在內容區內開始，
     * 拖出邊界仍然有效。
     */
    fun toDisplay(viewX: Float, viewY: Float): DisplayPoint = DisplayPoint(
        x = (viewX - contentLeft) * displayPerViewPixel,
        y = (viewY - contentTop) * displayPerViewPixel,
    )

    /** [toDisplay] 的反向：邏輯顯示座標 → view 座標。 */
    fun toView(displayX: Float, displayY: Float): ViewPoint = ViewPoint(
        x = contentLeft + displayX * scale,
        y = contentTop + displayY * scale,
    )

    /** 該 view 座標是否落在內容矩形內（而非 letterbox 黑邊）。 */
    fun isInside(viewX: Float, viewY: Float): Boolean =
        !isEmpty &&
            viewX >= contentLeft && viewX <= contentLeft + contentWidth &&
            viewY >= contentTop && viewY <= contentTop + contentHeight
}

/** 虛擬顯示**邏輯空間**中的一點，即 `injectMotionEvent` 使用的座標系。 */
data class DisplayPoint(val x: Float, val y: Float)

/** 鏡像容器 view 座標空間中的一點。 */
data class ViewPoint(val x: Float, val y: Float)

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
    val quarterTurns = rotation and 3
    // 旋轉 90/270 時邏輯顯示的長寬互換，但 surface 尺寸不變——內容是被旋轉「進」
    // 那個固定尺寸的 surface。
    val turned = isQuarterTurn(quarterTurns)
    val logicalWidth = (if (turned) surfaceHeight else surfaceWidth).toFloat()
    val logicalHeight = (if (turned) surfaceWidth else surfaceHeight).toFloat()

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

internal fun isQuarterTurn(quarterTurns: Int): Boolean =
    quarterTurns == 1 || quarterTurns == 3

private val EMPTY_VIEWPORT = Viewport(
    contentLeft = 0f,
    contentTop = 0f,
    contentWidth = 0f,
    contentHeight = 0f,
    scale = 0f,
    quarterTurns = 0,
)
