package com.xaxaxax.relc.ui.displaydetail

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 純資料，不得依賴 `android.graphics`——理由同 [Viewport]。
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** 拖曳中 left/right、top/bottom 可以互相跨過；命中判定與繪製前都要先轉正。 */
    fun normalized(): CropRect = CropRect(
        left = minOf(left, right),
        top = minOf(top, bottom),
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )
}

enum class DragHandle {
    TopLeft, TopRight, BottomLeft, BottomRight,
    Top, Bottom, Left, Right, Center, None
}

/**
 * 判斷觸點落在 [rect] 的哪個 handle 上；`rect` 為 null（尚未拉出裁切框）一律回 [DragHandle.None]。
 *
 * 角落用圓形距離、邊用單軸距離＋另一軸落在區間內，中心用「落在矩形內但不靠邊」；
 * 這個判斷順序本身就是優先權——角落先於邊，邊先於中心。
 */
fun hitTest(rect: CropRect?, x: Float, y: Float, handleRadius: Float): DragHandle {
    if (rect == null) return DragHandle.None
    val r = rect.normalized()

    fun nearPoint(px: Float, py: Float) = hypot(x - px, y - py) < handleRadius
    fun nearX(vx: Float) = abs(x - vx) < handleRadius
    fun nearY(vy: Float) = abs(y - vy) < handleRadius

    return when {
        nearPoint(r.left, r.top) -> DragHandle.TopLeft
        nearPoint(r.right, r.top) -> DragHandle.TopRight
        nearPoint(r.left, r.bottom) -> DragHandle.BottomLeft
        nearPoint(r.right, r.bottom) -> DragHandle.BottomRight
        nearX(r.left) && y in r.top..r.bottom -> DragHandle.Left
        nearX(r.right) && y in r.top..r.bottom -> DragHandle.Right
        nearY(r.top) && x in r.left..r.right -> DragHandle.Top
        nearY(r.bottom) && x in r.left..r.right -> DragHandle.Bottom
        x in r.left..r.right && y in r.top..r.bottom -> DragHandle.Center
        else -> DragHandle.None
    }
}

/** 依 [handle] 把拖曳量 (dx, dy) 套進對應的邊/角；未夾住的邊維持原值。 */
fun dragResize(rect: CropRect, handle: DragHandle, dx: Float, dy: Float): CropRect = when (handle) {
    DragHandle.TopLeft -> rect.copy(left = rect.left + dx, top = rect.top + dy)
    DragHandle.TopRight -> rect.copy(top = rect.top + dy, right = rect.right + dx)
    DragHandle.BottomLeft -> rect.copy(left = rect.left + dx, bottom = rect.bottom + dy)
    DragHandle.BottomRight -> rect.copy(right = rect.right + dx, bottom = rect.bottom + dy)
    DragHandle.Top -> rect.copy(top = rect.top + dy)
    DragHandle.Bottom -> rect.copy(bottom = rect.bottom + dy)
    DragHandle.Left -> rect.copy(left = rect.left + dx)
    DragHandle.Right -> rect.copy(right = rect.right + dx)
    DragHandle.Center -> rect.copy(
        left = rect.left + dx, top = rect.top + dy,
        right = rect.right + dx, bottom = rect.bottom + dy,
    )
    DragHandle.None -> rect
}
