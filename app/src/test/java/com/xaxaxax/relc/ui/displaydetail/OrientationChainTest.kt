package com.xaxaxax.relc.ui.displaydetail

import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationChainTest {

    @Test
    fun `a flat device keeps whatever rotation it had`() {
        // 手機平放在桌上就會回報 UNKNOWN；把它當成 0 會在使用者沒轉動時亂轉。
        assertEquals(
            Surface.ROTATION_90,
            quantizeOrientation(OrientationEventListener.ORIENTATION_UNKNOWN, Surface.ROTATION_90),
        )
    }

    @Test
    fun `angles well inside a quadrant adopt it`() {
        assertEquals(Surface.ROTATION_0, quantizeOrientation(0, Surface.ROTATION_180))
        assertEquals(Surface.ROTATION_270, quantizeOrientation(90, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_180, quantizeOrientation(180, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_90, quantizeOrientation(270, Surface.ROTATION_0))
    }

    @Test
    fun `a new quadrant is adopted only after clearing its boundary by the margin`() {
        // 象限邊界在 45 度，必須再越過 30 度（亦即到達 75 度）才採用 ROTATION_270。
        assertEquals(Surface.ROTATION_0, quantizeOrientation(50, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_0, quantizeOrientation(74, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_270, quantizeOrientation(76, Surface.ROTATION_0))

        // 另一側的邊界在 315 度，同樣要越過 30 度（到 285 度）。
        assertEquals(Surface.ROTATION_0, quantizeOrientation(310, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_90, quantizeOrientation(284, Surface.ROTATION_0))
    }

    @Test
    fun `the wrap-around boundary is handled like any other`() {
        // 350 度離 ROTATION_0 的中心（0）只有 10 度，跨過 360 的接縫也該切換。
        assertEquals(Surface.ROTATION_0, quantizeOrientation(350, Surface.ROTATION_90))
    }

    @Test
    fun `each display rotation maps to its own requested orientation`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientationFor(Surface.ROTATION_0),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationFor(Surface.ROTATION_90),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            requestedOrientationFor(Surface.ROTATION_180),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            requestedOrientationFor(Surface.ROTATION_270),
        )
    }
}
