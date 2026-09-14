package com.xaxaxax.relc.ui.displays

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaysScreenTest {

    @Test
    fun `within bounds is valid`() {
        assertTrue(isValidDisplayDimension(MIN_DISPLAY_DIMENSION_PX.toString()))
        assertTrue(isValidDisplayDimension(MAX_DISPLAY_DIMENSION_PX.toString()))
        assertTrue(isValidDisplayDimension("1080"))
    }

    @Test
    fun `below minimum is invalid`() {
        assertFalse(isValidDisplayDimension((MIN_DISPLAY_DIMENSION_PX - 1).toString()))
        assertFalse(isValidDisplayDimension("0"))
        assertFalse(isValidDisplayDimension("-100"))
    }

    @Test
    fun `above maximum is invalid`() {
        assertFalse(isValidDisplayDimension((MAX_DISPLAY_DIMENSION_PX + 1).toString()))
    }

    @Test
    fun `non-numeric or empty text is invalid`() {
        assertFalse(isValidDisplayDimension(""))
        assertFalse(isValidDisplayDimension("abc"))
        assertFalse(isValidDisplayDimension("12.5"))
    }

    @Test
    fun `dpi within bounds is valid`() {
        assertTrue(isValidDisplayDpi(MIN_DISPLAY_DPI.toString()))
        assertTrue(isValidDisplayDpi(MAX_DISPLAY_DPI.toString()))
        assertTrue(isValidDisplayDpi("320"))
    }

    @Test
    fun `dpi outside bounds is invalid`() {
        assertFalse(isValidDisplayDpi((MIN_DISPLAY_DPI - 1).toString()))
        assertFalse(isValidDisplayDpi((MAX_DISPLAY_DPI + 1).toString()))
        assertFalse(isValidDisplayDpi(""))
        assertFalse(isValidDisplayDpi("abc"))
    }
}
