package com.xaxaxax.relc.script

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayGeometryTest {

    /**
     * 未旋轉時 surface 空間與邏輯空間恆等——這也是為什麼這個 bug 只有畫面真的轉了才會現形，
     * 而 rotation 0 的測試永遠抓不到它。
     */
    @Test
    fun `unrotated logical size is already the surface size`() {
        assertEquals(1080 to 2400, DisplayGeometry.surfaceSize(1080, 2400, 0))
        assertEquals(1080 to 2400, DisplayGeometry.surfaceSize(1080, 2400, 2))
    }

    @Test
    fun `quarter turns swap back to the creation size`() {
        // 1080x2400 建立的顯示器轉了 90 度後，getDisplaySize 會回 2400x1080；
        // AImageReader 仍然必須以 1080x2400 開。
        assertEquals(1080 to 2400, DisplayGeometry.surfaceSize(2400, 1080, 1))
        assertEquals(1080 to 2400, DisplayGeometry.surfaceSize(2400, 1080, 3))
    }

    @Test
    fun `applying the swap twice returns to where it started`() {
        for (rotation in 0..3) {
            val (w, h) = DisplayGeometry.surfaceSize(2400, 1080, rotation)
            assertEquals(2400 to 1080, DisplayGeometry.surfaceSize(w, h, rotation))
        }
    }

    @Test
    fun `square displays are unaffected by rotation`() {
        for (rotation in 0..3) {
            assertEquals(1440 to 1440, DisplayGeometry.surfaceSize(1440, 1440, rotation))
        }
    }
}
