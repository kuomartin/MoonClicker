package com.xaxaxax.relc.ui.displaydetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    @Test
    fun `unrotated display matching the view exactly fills it`() {
        val viewport = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            rotation = 0,
            viewWidth = 1080, viewHeight = 2400,
        )

        assertEquals(0f, viewport.contentLeft, TOLERANCE)
        assertEquals(0f, viewport.contentTop, TOLERANCE)
        assertEquals(1080f, viewport.contentWidth, TOLERANCE)
        assertEquals(2400f, viewport.contentHeight, TOLERANCE)
        assertEquals(1f, viewport.scale, TOLERANCE)
        assertEquals(0f, viewport.viewRotationDegrees, TOLERANCE)
    }

    @Test
    fun `rotating the display swaps its logical dimensions and counter-rotates the view`() {
        // VD 建立為 1080x2400，鎖到 ROTATION_90 後邏輯顯示是 2400x1080（真機實測，見地圖 #9 前提 8）。
        // 手機仍為直向，故橫向內容等比縮小後上下留黑邊。
        val viewport = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            rotation = 1,
            viewWidth = 1080, viewHeight = 2400,
        )

        assertEquals(0.45f, viewport.scale, TOLERANCE)
        assertEquals(1080f, viewport.contentWidth, TOLERANCE)
        assertEquals(486f, viewport.contentHeight, TOLERANCE)
        assertEquals(0f, viewport.contentLeft, TOLERANCE)
        assertEquals(957f, viewport.contentTop, TOLERANCE)
        assertEquals(-90f, viewport.viewRotationDegrees, TOLERANCE)
    }

    @Test
    fun `surface view layout box is swapped so that rotating it covers the content rect`() {
        val upright = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            rotation = 0,
            viewWidth = 1080, viewHeight = 2400,
        )
        assertEquals(upright.contentWidth, upright.surfaceViewWidth, TOLERANCE)
        assertEquals(upright.contentHeight, upright.surfaceViewHeight, TOLERANCE)

        val quarterTurned = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            rotation = 1,
            viewWidth = 1080, viewHeight = 2400,
        )
        // 內容區是 1080x486；未旋轉的佈局框必須是 486x1080，轉 -90 後才蓋得住。
        assertEquals(486f, quarterTurned.surfaceViewWidth, TOLERANCE)
        assertEquals(1080f, quarterTurned.surfaceViewHeight, TOLERANCE)
    }

    @Test
    fun `content rect corners map onto the logical display corners`() {
        // 內容矩形位於 (0, 957)，大小 1080x486；邏輯顯示為 2400x1080。
        val viewport = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            rotation = 1,
            viewWidth = 1080, viewHeight = 2400,
        )

        val topLeft = viewport.toDisplay(0f, 957f)
        assertEquals(0f, topLeft.x, TOLERANCE)
        assertEquals(0f, topLeft.y, TOLERANCE)

        val bottomRight = viewport.toDisplay(1080f, 1443f)
        assertEquals(2400f, bottomRight.x, TOLERANCE)
        assertEquals(1080f, bottomRight.y, TOLERANCE)
    }

    @Test
    fun `containment is a separate question from mapping`() {
        val viewport = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            rotation = 1,
            viewWidth = 1080, viewHeight = 2400,
        )

        assertTrue(viewport.isInside(540f, 1200f))
        // 上方黑邊：映射仍然有定義（負的邏輯座標），只是不在內容區裡。
        assertFalse(viewport.isInside(540f, 100f))
        assertEquals(-1904.44f, viewport.toDisplay(540f, 100f).y, TOLERANCE)
    }

    @Test
    fun `degenerate inputs yield an empty viewport instead of throwing`() {
        // getDisplaySize 回 [0, 0]，或 view 尚未 measure。
        val noDisplay = viewportOf(0, 0, rotation = 0, viewWidth = 1080, viewHeight = 2400)
        val noView = viewportOf(1080, 2400, rotation = 0, viewWidth = 0, viewHeight = 0)

        for (viewport in listOf(noDisplay, noView)) {
            assertTrue(viewport.isEmpty)
            assertEquals(0f, viewport.contentWidth, TOLERANCE)
            assertEquals(0f, viewport.contentHeight, TOLERANCE)
            // 觸控側自然把所有點判為黑邊外，呈現側畫出全黑。
            assertFalse(viewport.isInside(0f, 0f))
            assertFalse(viewport.isInside(540f, 1200f))
        }
    }

    @Test
    fun `content rect corners map to the logical corners at every rotation`() {
        for (rotation in 0..3) {
            val viewport = viewportOf(1080, 2400, rotation, viewWidth = 1440, viewHeight = 1440)
            val swapped = rotation == 1 || rotation == 3
            val logicalWidth = if (swapped) 2400f else 1080f
            val logicalHeight = if (swapped) 1080f else 2400f

            val topLeft = viewport.toDisplay(viewport.contentLeft, viewport.contentTop)
            assertEquals("rotation $rotation", 0f, topLeft.x, TOLERANCE)
            assertEquals("rotation $rotation", 0f, topLeft.y, TOLERANCE)

            val bottomRight = viewport.toDisplay(
                viewport.contentLeft + viewport.contentWidth,
                viewport.contentTop + viewport.contentHeight,
            )
            assertEquals("rotation $rotation", logicalWidth, bottomRight.x, TOLERANCE)
            assertEquals("rotation $rotation", logicalHeight, bottomRight.y, TOLERANCE)
        }
    }

    @Test
    fun `matching aspect ratios leave no letterbox at all`() {
        // 同比例但不同大小 —— 不得因浮點誤差生出 1px 黑邊。
        val viewport = viewportOf(1080, 2400, rotation = 0, viewWidth = 540, viewHeight = 1200)

        assertEquals(0f, viewport.contentLeft, TOLERANCE)
        assertEquals(0f, viewport.contentTop, TOLERANCE)
        assertEquals(540f, viewport.contentWidth, TOLERANCE)
        assertEquals(1200f, viewport.contentHeight, TOLERANCE)
    }

    @Test
    fun `a display narrower than the view is letterboxed left and right`() {
        // 手機橫向、VD 仍直向 —— 最初回報的症狀，正確行為是左右黑邊而非拉伸。
        val viewport = viewportOf(1080, 2400, rotation = 0, viewWidth = 2400, viewHeight = 1080)

        assertEquals(0.45f, viewport.scale, TOLERANCE)
        assertEquals(486f, viewport.contentWidth, TOLERANCE)
        assertEquals(1080f, viewport.contentHeight, TOLERANCE)
        assertEquals(957f, viewport.contentLeft, TOLERANCE)
        assertEquals(0f, viewport.contentTop, TOLERANCE)
    }

    @Test
    fun `the display is scaled up when the view is larger`() {
        val viewport = viewportOf(540, 1200, rotation = 0, viewWidth = 1080, viewHeight = 2400)

        assertEquals(2f, viewport.scale, TOLERANCE)
        assertEquals(1080f, viewport.contentWidth, TOLERANCE)
        assertEquals(2400f, viewport.contentHeight, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
