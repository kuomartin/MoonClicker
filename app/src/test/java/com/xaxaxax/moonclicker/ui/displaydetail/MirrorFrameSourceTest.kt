package com.xaxaxax.moonclicker.ui.displaydetail

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只守 [MirrorFrameSource] 登記／解除登記那一半——它決定 `/mirror/{displayId}` 是串流還是
 * 404，而且不碰 `Bitmap`/`TextureView`，純 JVM 測得到。擷取、轉正、JPEG 編碼那一半碰的是真的
 * `android.graphics.Bitmap`，`app/src/test` 沒有 Robolectric（沿用 #52 的結論），走實機驗證。
 */
class MirrorFrameSourceTest {

    private val source = MirrorFrameSource()

    /** 永遠回 null 的擷取來源：這些測試只看登記狀態，不需要真的畫面。 */
    private val idleCapture = MirrorFrameSource.Capture { null }

    @Test
    fun `an unregistered display has no frames`() {
        assertNull(source.frames(2))
    }

    @Test
    fun `registering a display makes frames available`() {
        source.register(2, idleCapture)

        assertNotNull(source.frames(2))
    }

    @Test
    fun `registering one display leaves the others alone`() {
        source.register(2, idleCapture)

        assertNull(source.frames(3))
    }

    @Test
    fun `unregistering a display takes its frames away again`() {
        source.register(2, idleCapture)
        source.unregister(2)

        assertNull(source.frames(2))
    }

    @Test
    fun `re-registering after unregister makes frames available again`() {
        source.register(2, idleCapture)
        source.unregister(2)
        source.register(2, idleCapture)

        assertNotNull(source.frames(2))
    }

    @Test
    fun `a frame signal for a display nobody registered does not conjure a stream`() {
        source.onFrameAvailable(2)

        assertNull(source.frames(2))
    }
}
