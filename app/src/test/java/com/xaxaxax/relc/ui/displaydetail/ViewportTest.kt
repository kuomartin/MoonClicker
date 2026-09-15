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
            d = 0,
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
    fun `rotating the host display swaps its logical dimensions and counter-rotates the view`() {
        // MainDisplay 轉到 ROTATION_90，VD 的 buffer 仍是 1080x2400——letterbox 依 d 互換。
        val viewport = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            d = 1,
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
    fun `the unrotated layout box is swapped so that rotating it covers the content rect`() {
        val upright = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            d = 0,
            viewWidth = 1080, viewHeight = 2400,
        )
        assertEquals(upright.contentWidth, upright.unrotatedWidth, TOLERANCE)
        assertEquals(upright.contentHeight, upright.unrotatedHeight, TOLERANCE)

        val quarterTurned = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            d = 1,
            viewWidth = 1080, viewHeight = 2400,
        )
        // 內容區是 1080x486；未旋轉的佈局框必須是 486x1080，轉 -90 後才蓋得住。
        assertEquals(486f, quarterTurned.unrotatedWidth, TOLERANCE)
        assertEquals(1080f, quarterTurned.unrotatedHeight, TOLERANCE)
    }

    @Test
    fun `content rect corners map onto the buffer corners`() {
        // 內容矩形位於 (0, 957)，大小 1080x486；d=1 時 letterbox 用的邏輯尺寸是 2400x1080。
        val viewport = viewportOf(
            surfaceWidth = 1080, surfaceHeight = 2400,
            d = 1,
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
            d = 1,
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
        val noDisplay = viewportOf(0, 0, d = 0, viewWidth = 1080, viewHeight = 2400)
        val noView = viewportOf(1080, 2400, d = 0, viewWidth = 0, viewHeight = 0)

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
    fun `content rect corners map to the buffer corners at every host rotation`() {
        for (d in 0..3) {
            val viewport = viewportOf(1080, 2400, d, viewWidth = 1440, viewHeight = 1440)
            val swapped = d == 1 || d == 3
            val logicalWidth = if (swapped) 2400f else 1080f
            val logicalHeight = if (swapped) 1080f else 2400f

            val topLeft = viewport.toDisplay(viewport.contentLeft, viewport.contentTop)
            assertEquals("d=$d", 0f, topLeft.x, TOLERANCE)
            assertEquals("d=$d", 0f, topLeft.y, TOLERANCE)

            val bottomRight = viewport.toDisplay(
                viewport.contentLeft + viewport.contentWidth,
                viewport.contentTop + viewport.contentHeight,
            )
            assertEquals("d=$d", logicalWidth, bottomRight.x, TOLERANCE)
            assertEquals("d=$d", logicalHeight, bottomRight.y, TOLERANCE)
        }
    }

    @Test
    fun `matching aspect ratios leave no letterbox at all`() {
        // 同比例但不同大小 —— 不得因浮點誤差生出 1px 黑邊。
        val viewport = viewportOf(1080, 2400, d = 0, viewWidth = 540, viewHeight = 1200)

        assertEquals(0f, viewport.contentLeft, TOLERANCE)
        assertEquals(0f, viewport.contentTop, TOLERANCE)
        assertEquals(540f, viewport.contentWidth, TOLERANCE)
        assertEquals(1200f, viewport.contentHeight, TOLERANCE)
    }

    @Test
    fun `a display narrower than the view is letterboxed left and right`() {
        // 手機橫向、VD 仍直向 —— 最初回報的症狀，正確行為是左右黑邊而非拉伸。
        val viewport = viewportOf(1080, 2400, d = 0, viewWidth = 2400, viewHeight = 1080)

        assertEquals(0.45f, viewport.scale, TOLERANCE)
        assertEquals(486f, viewport.contentWidth, TOLERANCE)
        assertEquals(1080f, viewport.contentHeight, TOLERANCE)
        assertEquals(957f, viewport.contentLeft, TOLERANCE)
        assertEquals(0f, viewport.contentTop, TOLERANCE)
    }

    @Test
    fun `the display is scaled up when the view is larger`() {
        val viewport = viewportOf(540, 1200, d = 0, viewWidth = 1080, viewHeight = 2400)

        assertEquals(2f, viewport.scale, TOLERANCE)
        assertEquals(1080f, viewport.contentWidth, TOLERANCE)
        assertEquals(2400f, viewport.contentHeight, TOLERANCE)
    }

    @Test
    fun `each host rotation has its own counter-rotation and layout box`() {
        // surface 1080x2400 投影進 1440x1440 的 view，scale 恆為 0.6。
        // 內容尺寸只分得出 {0,2} 與 {1,3}；旋轉角度才分得出全部四個。
        val expected = mapOf(
            0 to Rotation(degrees = 0f, contentW = 648f, contentH = 1440f),
            1 to Rotation(degrees = -90f, contentW = 1440f, contentH = 648f),
            2 to Rotation(degrees = -180f, contentW = 648f, contentH = 1440f),
            3 to Rotation(degrees = -270f, contentW = 1440f, contentH = 648f),
        )

        for ((d, want) in expected) {
            val viewport = viewportOf(1080, 2400, d, viewWidth = 1440, viewHeight = 1440)
            assertEquals("d=$d", want.degrees, viewport.viewRotationDegrees, TOLERANCE)
            assertEquals("d=$d", want.contentW, viewport.contentWidth, TOLERANCE)
            assertEquals("d=$d", want.contentH, viewport.contentHeight, TOLERANCE)
            // 未旋轉的佈局框永遠是內容矩形的「直立」版本，四個方向都一樣。
            assertEquals("d=$d", 648f, viewport.unrotatedWidth, TOLERANCE)
            assertEquals("d=$d", 1440f, viewport.unrotatedHeight, TOLERANCE)
        }
    }

    @Test
    fun `mapping to the display and back is a round trip at every host rotation`() {
        for (d in 0..3) {
            val viewport = viewportOf(1080, 2400, d, viewWidth = 1440, viewHeight = 1440)
            val probes = listOf(
                viewport.contentLeft to viewport.contentTop,
                viewport.contentLeft + viewport.contentWidth / 3f to
                    viewport.contentTop + viewport.contentHeight / 7f,
                viewport.contentLeft + viewport.contentWidth to
                    viewport.contentTop + viewport.contentHeight,
            )
            for ((viewX, viewY) in probes) {
                val display = viewport.toDisplay(viewX, viewY)
                val back = viewport.toView(display.x, display.y)
                assertEquals("d=$d", viewX, back.x, ROUND_TRIP_TOLERANCE)
                assertEquals("d=$d", viewY, back.y, ROUND_TRIP_TOLERANCE)
            }
        }
    }

    @Test
    fun `content-local coordinates reach the buffer by the scale factor alone`() {
        // TouchForwarder 的 Matrix 先做 setScale(displayPerViewPixel, ...)，因為觸控節點
        // 收到的座標已是內容矩形內的相對座標。這裡用獨立算出的數字把那個係數釘住。
        val viewport = viewportOf(1080, 2400, d = 1, viewWidth = 1080, viewHeight = 2400)

        // 邏輯 2400x1080 投影成 1080x486，故一個 view 像素等於 1 / 0.45 個邏輯像素。
        assertEquals(2.2222f, viewport.displayPerViewPixel, TOLERANCE)

        val fromContentOrigin =
            viewport.toDisplay(viewport.contentLeft + 300f, viewport.contentTop + 120f)
        assertEquals(666.67f, fromContentOrigin.x, 0.1f)
        assertEquals(266.67f, fromContentOrigin.y, 0.1f)
    }

    @Test
    fun `viewport geometry does not depend on v`() {
        // ADR-0014 的核心不變量：letterbox 尺寸、view 旋轉只看 d。同一個 d，
        // viewportOf 沒有地方可以收到 v，這個測試釘住「沒有這個參數」本身就是保證。
        val a = viewportOf(1080, 2400, d = 1, viewWidth = 1080, viewHeight = 2400)
        val b = viewportOf(1080, 2400, d = 1, viewWidth = 1080, viewHeight = 2400)
        assertEquals(a, b)
    }

    @Test
    fun `rotateQuarterTurn maps buffer points into VD's logical space at every v`() {
        // buffer 1080x2400，四個角在每個 v 都要落在（互換後）邏輯矩形的四個角上。
        val width = 1080f
        val height = 2400f

        assertEquals(DisplayPoint(0f, 0f), rotateQuarterTurn(0f, 0f, width, height, quarterTurns = 0))
        assertEquals(DisplayPoint(width, height), rotateQuarterTurn(width, height, width, height, quarterTurns = 0))

        // v=1：邏輯尺寸互換成 2400x1080，buffer 右上角 (width, 0) 轉到邏輯左上角。
        assertEquals(DisplayPoint(0f, 0f), rotateQuarterTurn(width, 0f, width, height, quarterTurns = 1))
        assertEquals(DisplayPoint(height, width), rotateQuarterTurn(0f, height, width, height, quarterTurns = 1))

        // v=2：buffer 右下角轉到邏輯左上角。
        assertEquals(DisplayPoint(0f, 0f), rotateQuarterTurn(width, height, width, height, quarterTurns = 2))
        assertEquals(DisplayPoint(width, height), rotateQuarterTurn(0f, 0f, width, height, quarterTurns = 2))

        // v=3：buffer 左下角轉到邏輯左上角。
        assertEquals(DisplayPoint(0f, 0f), rotateQuarterTurn(0f, height, width, height, quarterTurns = 3))
        assertEquals(DisplayPoint(height, width), rotateQuarterTurn(width, 0f, width, height, quarterTurns = 3))
    }

    @Test
    fun `rotateQuarterTurn inverts cleanly at every quarter turn`() {
        // touchTransform 用「(4-d) mod 4、長寬互換規則跟著反過來」反轉 d 那一段旋轉。
        // 這裡不跑 Matrix（app 的 stub 會丟 not mocked），直接釘住這個反函式規則本身。
        val width = 1080f
        val height = 2400f
        val points = listOf(0f to 0f, width to 0f, 0f to height, width to height, 300f to 777f)

        for (quarterTurns in 0..3) {
            val inverseTurns = (4 - quarterTurns) % 4
            val (inverseWidth, inverseHeight) =
                if (isQuarterTurn(quarterTurns)) height to width else width to height

            for ((px, py) in points) {
                val logical = rotateQuarterTurn(px, py, width, height, quarterTurns)
                val back = rotateQuarterTurn(logical.x, logical.y, inverseWidth, inverseHeight, inverseTurns)
                assertEquals("quarterTurns=$quarterTurns", px, back.x, TOLERANCE)
                assertEquals("quarterTurns=$quarterTurns", py, back.y, TOLERANCE)
            }
        }
    }

    private data class Rotation(val degrees: Float, val contentW: Float, val contentH: Float)

    private companion object {
        const val TOLERANCE = 0.01f
        const val ROUND_TRIP_TOLERANCE = 1f
    }
}
