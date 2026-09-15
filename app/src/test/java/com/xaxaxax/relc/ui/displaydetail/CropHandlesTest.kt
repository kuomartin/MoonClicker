package com.xaxaxax.relc.ui.displaydetail

import org.junit.Assert.assertEquals
import org.junit.Test

class CropHandlesTest {

    private val rect = CropRect(left = 100f, top = 100f, right = 300f, bottom = 200f)

    @Test
    fun `no rect always misses`() {
        assertEquals(DragHandle.None, hitTest(null, 100f, 100f, RADIUS))
    }

    @Test
    fun `each corner is hit within radius`() {
        assertEquals(DragHandle.TopLeft, hitTest(rect, rect.left, rect.top, RADIUS))
        assertEquals(DragHandle.TopRight, hitTest(rect, rect.right, rect.top, RADIUS))
        assertEquals(DragHandle.BottomLeft, hitTest(rect, rect.left, rect.bottom, RADIUS))
        assertEquals(DragHandle.BottomRight, hitTest(rect, rect.right, rect.bottom, RADIUS))
    }

    @Test
    fun `each edge midpoint is hit when clear of the corners`() {
        val midY = (rect.top + rect.bottom) / 2f
        val midX = (rect.left + rect.right) / 2f

        assertEquals(DragHandle.Left, hitTest(rect, rect.left, midY, RADIUS))
        assertEquals(DragHandle.Right, hitTest(rect, rect.right, midY, RADIUS))
        assertEquals(DragHandle.Top, hitTest(rect, midX, rect.top, RADIUS))
        assertEquals(DragHandle.Bottom, hitTest(rect, midX, rect.bottom, RADIUS))
    }

    @Test
    fun `interior away from every edge is the center handle`() {
        assertEquals(DragHandle.Center, hitTest(rect, (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, RADIUS))
    }

    @Test
    fun `outside the rect and its radius is a miss`() {
        assertEquals(DragHandle.None, hitTest(rect, rect.left - RADIUS - 1f, rect.top - RADIUS - 1f, RADIUS))
        assertEquals(DragHandle.None, hitTest(rect, rect.right + RADIUS + 5f, rect.bottom + RADIUS + 5f, RADIUS))
    }

    @Test
    fun `corners win over edges when both are within radius`() {
        // 角落判定先於邊——同一個點同時落在 "靠近 left" 與 "靠近 top" 的半徑內時，
        // 必須回角落而不是任一條邊。
        assertEquals(DragHandle.TopLeft, hitTest(rect, rect.left + 1f, rect.top + 1f, RADIUS))
    }

    @Test
    fun `hit testing works the same on a rect dragged inside-out`() {
        val inverted = CropRect(left = rect.right, top = rect.bottom, right = rect.left, bottom = rect.top)
        assertEquals(DragHandle.TopLeft, hitTest(inverted, rect.left, rect.top, RADIUS))
        assertEquals(DragHandle.Center, hitTest(inverted, (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, RADIUS))
    }

    @Test
    fun `dragResize moves only the edges owned by the handle`() {
        val moved = dragResize(rect, DragHandle.TopLeft, dx = 10f, dy = -5f)
        assertEquals(CropRect(left = 110f, top = 95f, right = 300f, bottom = 200f), moved)

        val right = dragResize(rect, DragHandle.Right, dx = 20f, dy = 999f)
        assertEquals(CropRect(left = 100f, top = 100f, right = 320f, bottom = 200f), right)

        val top = dragResize(rect, DragHandle.Top, dx = 999f, dy = 15f)
        assertEquals(CropRect(left = 100f, top = 115f, right = 300f, bottom = 200f), top)
    }

    @Test
    fun `dragResize on the center translates the whole rect`() {
        val moved = dragResize(rect, DragHandle.Center, dx = 30f, dy = -10f)
        assertEquals(CropRect(left = 130f, top = 90f, right = 330f, bottom = 190f), moved)
    }

    @Test
    fun `dragResize on none is a no-op`() {
        assertEquals(rect, dragResize(rect, DragHandle.None, dx = 50f, dy = 50f))
    }

    @Test
    fun `normalized swaps crossed edges back into left-le-right, top-le-bottom order`() {
        val crossed = CropRect(left = 300f, top = 200f, right = 100f, bottom = 100f)
        assertEquals(rect, crossed.normalized())
        assertEquals(rect, rect.normalized())
    }

    private companion object {
        const val RADIUS = 24f
    }
}
