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
    fun `angles near a boundary keep the current rotation`() {
        // 50 度落在 ROTATION_270 的象限裡，但離其中心（90）有 40 度 —— 超出 30 度的遲滯帶，
        // 因此不切換。這是防止邊界抖動的那道閘。
        assertEquals(Surface.ROTATION_0, quantizeOrientation(50, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_0, quantizeOrientation(310, Surface.ROTATION_0))
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
