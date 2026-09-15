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
    /**
     * MainDisplay（display 0）相對自然方向轉了幾個直角（`Surface.ROTATION_*`，0..3）。
     *
     * ADR-0014：VD 自己的 rotation 不影響它的 buffer 尺寸（`DisplayGeometry.surfaceWidth`
     * 建立後不隨旋轉改變），只影響畫進那個固定畫布裡的內容朝向；決定內容矩形的 letterbox
     * 尺寸、以及套在鏡像 view 上的反向旋轉的，只有 d。
     */
    val d: Int,
) {
    /** 沒有可畫的內容 —— 虛擬顯示尺寸未知，或 view 尚未 measure。 */
    val isEmpty: Boolean get() = contentWidth <= 0f || contentHeight <= 0f

    /** d 是否轉了直角（長寬因此互換）。 */
    val isDQuarterTurned: Boolean get() = isQuarterTurn(d)

    /** 要套用到鏡像 view 的旋轉角度，用於抵銷系統對整個 view 空間套的 d 旋轉，把鏡像釘在面板座標。 */
    val viewRotationDegrees: Float get() = -(d * 90f)

    /**
     * 鏡像 view **未旋轉時**的佈局尺寸。旋轉 90/270 後外接矩形長寬互換，
     * 因此這裡要先互換，套上 [viewRotationDegrees] 之後才剛好蓋住內容矩形。
     */
    val unrotatedWidth: Float get() = if (isDQuarterTurned) contentHeight else contentWidth
    val unrotatedHeight: Float get() = if (isDQuarterTurned) contentWidth else contentHeight

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

/**
 * VD **buffer 空間**中的一點——[Viewport] 不知道 v（VD 自己的 rotation），只知道 d，所以這裡
 * 停在 buffer 的原始座標系。`injectMotionEvent` 要的是 VD 目前的**邏輯空間**（隨 v 互換長寬），
 * 那一層 v 旋轉不在 [Viewport] 裡，由 `VirtualDisplayMirror.kt` 的 `touchTransform` 疊加。
 */
data class DisplayPoint(val x: Float, val y: Float)

/** 鏡像容器 view 座標空間中的一點。 */
data class ViewPoint(val x: Float, val y: Float)

/**
 * @param surfaceWidth 虛擬顯示**建立時**的尺寸，不隨旋轉改變（`Display.getMode()`）。
 * @param d MainDisplay（display 0）的 `Surface.ROTATION_*`；決定鏡像在面板座標中的 letterbox
 *   尺寸與反向旋轉，跟 VD 自己的 rotation 無關（見 [Viewport.d]）。
 */
fun viewportOf(
    surfaceWidth: Int,
    surfaceHeight: Int,
    d: Int,
    viewWidth: Int,
    viewHeight: Int,
): Viewport {
    val dTurns = d and 3
    // 旋轉 90/270 時鏡像在面板上的外接矩形長寬互換，但 VD 的 buffer 尺寸不變——
    // 互換的是我們套的 -d·90 之後的落地形狀，不是 surface 本身。
    val turned = isQuarterTurn(dTurns)
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
        d = dTurns,
    )
}

internal fun isQuarterTurn(quarterTurns: Int): Boolean =
    quarterTurns == 1 || quarterTurns == 3

/**
 * 一個 quarter-turn 仿射變換的係數，row-major、9 元素（`android.graphics.Matrix.setValues`
 * 的格式）：`x' = c[0]·x + c[1]·y + c[2]`，`y' = c[3]·x + c[4]·y + c[5]`。
 *
 * 觸控（[rotateQuarterTurn]）與畫面／bitmap（`VirtualDisplayMirror.kt` 的 `quarterTurnMatrix`）
 * 兩邊都只是這份係數的 adapter，不各自重新推導一次仿射變換。
 */
internal fun quarterTurnCoefficients(quarterTurns: Int, width: Float, height: Float): FloatArray =
    when (quarterTurns and 3) {
        1 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f)
        2 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f)
        3 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

/**
 * buffer 空間中的一點旋轉 [quarterTurns] 個直角（`Surface.ROTATION_*`，VD 自己的 rotation）
 * 到 VD 目前的邏輯空間，即 `injectMotionEvent` 要的座標系。[quarterTurnCoefficients] 的純
 * Kotlin adapter，供測試把四個方向的公式釘住（`Matrix` 在 `app/src/test` 會丟 `not mocked`）。
 */
internal fun rotateQuarterTurn(px: Float, py: Float, width: Float, height: Float, quarterTurns: Int): DisplayPoint {
    val c = quarterTurnCoefficients(quarterTurns, width, height)
    return DisplayPoint(c[0] * px + c[1] * py + c[2], c[3] * px + c[4] * py + c[5])
}

private val EMPTY_VIEWPORT = Viewport(
    contentLeft = 0f,
    contentTop = 0f,
    contentWidth = 0f,
    contentHeight = 0f,
    scale = 0f,
    d = 0,
)
