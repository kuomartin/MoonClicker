package com.xaxaxax.relc.script

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `matchesSize` 決定沿用現有虛擬顯示的規則：跟建立尺寸精確比對，不接受長寬互換
 * （issue #22）。互換曾經被刻意接受，代價是腳本可能跑在 surface 幾何相反的顯示器上。
 */
class ScriptSessionTest {

    @Test
    fun `exact match is accepted`() {
        assertTrue(matchesSize(intArrayOf(1080, 2400), width = 1080, height = 2400))
    }

    @Test
    fun `swapped width and height is rejected`() {
        // 這是這次修正的核心行為變更：以前這個案例回 true。
        assertFalse(matchesSize(intArrayOf(2400, 1080), width = 1080, height = 2400))
    }

    @Test
    fun `different size is rejected`() {
        assertFalse(matchesSize(intArrayOf(1080, 1920), width = 1080, height = 2400))
    }

    @Test
    fun `null size is rejected`() {
        assertFalse(matchesSize(null, width = 1080, height = 2400))
    }

    @Test
    fun `a short array is rejected rather than throwing`() {
        assertFalse(matchesSize(intArrayOf(0), width = 0, height = 0))
    }
}
