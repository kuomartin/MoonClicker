package com.xaxaxax.relc.ui.displaydetail

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayThumbnailCacheTest {

    @Test
    fun `already within max long edge is unchanged`() {
        assertEquals(300 to 200, computeThumbnailTargetSize(300, 200, 400))
    }

    @Test
    fun `landscape source scales down keeping aspect ratio`() {
        assertEquals(400 to 225, computeThumbnailTargetSize(1920, 1080, 400))
    }

    @Test
    fun `portrait source scales down keeping aspect ratio`() {
        assertEquals(225 to 400, computeThumbnailTargetSize(1080, 1920, 400))
    }

    @Test
    fun `square source scales to max long edge`() {
        assertEquals(400 to 400, computeThumbnailTargetSize(1000, 1000, 400))
    }

    @Test
    fun `scaled dimensions never collapse to zero`() {
        val (width, height) = computeThumbnailTargetSize(10000, 1, 400)
        assertEquals(400, width)
        assertEquals(1, height)
    }
}
