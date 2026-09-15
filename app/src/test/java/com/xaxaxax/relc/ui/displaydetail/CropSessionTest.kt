package com.xaxaxax.relc.ui.displaydetail

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropSessionTest {

    // context 只有 confirmSave 的裁切存檔那段會用到（見 Viewport.kt 對 android.graphics
    // 在 JVM 測試下的限制說明）；這裡測的狀態轉換都不會碰它，mockk 給一個空殼即可。
    private val session = CropSession(mockk<Context>(relaxed = true))

    @Test
    fun `dragging from empty starts a new rect anchored at the drag origin`() {
        session.start()

        session.onDragStart(100f, 100f, handleRadius = 24f)

        val state = session.state.value
        assertEquals(CropRect(100f, 100f, 100f, 100f), state.cropRect)
        assertEquals(DragHandle.BottomRight, state.activeHandle)
    }

    @Test
    fun `dragging an existing handle resizes instead of starting a new rect`() {
        session.start()
        session.onDragStart(100f, 100f, handleRadius = 24f) // 拉出初始框
        session.onDragEnd()
        session.onDragStart(100f, 100f, handleRadius = 24f) // 再次落在左上角 handle 上

        assertEquals(DragHandle.TopLeft, session.state.value.activeHandle)

        session.onDrag(dx = 10f, dy = -5f)

        assertEquals(CropRect(110f, 95f, 100f, 100f), session.state.value.cropRect)
    }

    @Test
    fun `drag end normalizes a crossed rect and clears the active handle`() {
        session.start()
        session.onDragStart(300f, 200f, handleRadius = 24f)
        session.onDrag(dx = -200f, dy = -100f) // 拖成 (100,100)-(300,200) 交叉成負寬高

        session.onDragEnd()

        val state = session.state.value
        assertEquals(CropRect(100f, 100f, 300f, 200f), state.cropRect)
        assertEquals(DragHandle.None, state.activeHandle)
    }

    @Test
    fun `canSave is false until the rect has non-zero area`() {
        session.start()
        assertFalse(session.state.value.canSave)

        session.onDragStart(100f, 100f, handleRadius = 24f) // 零面積（單點）
        assertFalse(session.state.value.canSave)

        session.onDrag(dx = 100f, dy = 50f)
        assertTrue(session.state.value.canSave)
    }

    @Test
    fun `cancel resets to an inactive session with no crop state`() {
        session.start()
        session.onDragStart(100f, 100f, handleRadius = 24f)

        session.cancel()

        val state = session.state.value
        assertFalse(state.isActive)
        assertEquals(null, state.cropRect)
        assertEquals(null, state.bitmap)
    }

    @Test
    fun `cropRectToBitmapRect maps a view-space rect through the viewport to bitmap pixels`() {
        // bitmap 1080x2400 投影進 1080x2400 的畫布，viewport 恆等（無 letterbox）。
        val viewport = viewportOf(1080, 2400, d = 0, viewWidth = 1080, viewHeight = 2400)
        val rect = CropRect(left = 100f, top = 200f, right = 500f, bottom = 600f)

        assertEquals(PixelRect(100, 200, 500, 600), cropRectToBitmapRect(rect, viewport))
    }

    @Test
    fun `cropRectToBitmapRect accounts for letterbox scale`() {
        // bitmap 1080x2400 投影進 540x1200 的畫布，scale 0.5——view 座標要乘 2 才是 bitmap 像素。
        val viewport = viewportOf(1080, 2400, d = 0, viewWidth = 540, viewHeight = 1200)
        val rect = CropRect(left = 50f, top = 100f, right = 250f, bottom = 300f)

        assertEquals(PixelRect(100, 200, 500, 600), cropRectToBitmapRect(rect, viewport))
    }
}
