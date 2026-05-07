package com.xaxaxax.relc.display

import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayControllerTest {

    // ─── 測試用 fixtures ──────────────────────────────────────────────────────

    private val config = DisplayConfig(name = "test", width = 1080, height = 1920, densityDpi = 320)
    private val FAKE_DISPLAY_ID = 42

    private fun mockService(returnDisplayId: Int = FAKE_DISPLAY_ID): IRelcShizukuService =
        mockk<IRelcShizukuService>().also {
            every { it.createVirtualDisplay(any(), any(), any(), any(), any()) } returns returnDisplayId
            every { it.setVirtualDisplaySurface(any(), any()) } returns true
            every { it.destroyVirtualDisplay(any()) } returns true
        }

    // ─── create() ────────────────────────────────────────────────────────────

    @Test
    fun `create with NoOpSink passes null surface to service`() {
        val service = mockService()
        val controller = VirtualDisplayController(service)

        controller.create(config, NoOpSink())

        // surface 必須是 null（NoOpSink 不捕捉畫面）
        verify { service.createVirtualDisplay("test", 1080, 1920, 320, null) }
    }

    @Test
    fun `create stores returned displayId`() {
        val controller = VirtualDisplayController(mockService(returnDisplayId = 7))

        controller.create(config)

        assertEquals(7, controller.displayId)
    }

    @Test
    fun `create transitions state from IDLE to CREATED`() {
        val controller = VirtualDisplayController(mockService())
        assertEquals(VirtualDisplayController.State.IDLE, controller.state)

        controller.create(config)

        assertEquals(VirtualDisplayController.State.CREATED, controller.state)
    }

    @Test(expected = IllegalStateException::class)
    fun `create twice throws`() {
        val controller = VirtualDisplayController(mockService())
        controller.create(config)
        controller.create(config) // 第二次應該 throw
    }

    @Test(expected = IllegalStateException::class)
    fun `create with INVALID_DISPLAY response throws`() {
        val service = mockService(returnDisplayId = -1) // Display.INVALID_DISPLAY
        VirtualDisplayController(service).create(config)
    }

    // ─── replaceSink() ────────────────────────────────────────────────────────

    @Test
    fun `replaceSink stops old sink before starting new one`() {
        val service = mockService()
        val controller = VirtualDisplayController(service)
        val oldSink = mockk<DisplaySink>(relaxed = true).also {
            every { it.acquireSurface() } returns null
        }
        val newSink = mockk<DisplaySink>(relaxed = true).also {
            every { it.acquireSurface() } returns null
        }

        controller.create(config, oldSink)
        controller.replaceSink(newSink)

        // 順序：old.stop → setVirtualDisplaySurface → new.start
        verifyOrder {
            oldSink.stop()
            service.setVirtualDisplaySurface(FAKE_DISPLAY_ID, null)
            newSink.start()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `replaceSink before create throws`() {
        VirtualDisplayController(mockService()).replaceSink(NoOpSink())
    }

    // ─── destroy() ───────────────────────────────────────────────────────────

    @Test
    fun `destroy calls service and transitions to DESTROYED`() {
        val service = mockService()
        val controller = VirtualDisplayController(service)
        controller.create(config)

        controller.destroy()

        verify { service.destroyVirtualDisplay(FAKE_DISPLAY_ID) }
        assertEquals(VirtualDisplayController.State.DESTROYED, controller.state)
    }

    @Test
    fun `destroy stops and releases sink`() {
        val service = mockService()
        val sink = mockk<DisplaySink>(relaxed = true).also {
            every { it.acquireSurface() } returns null
        }
        val controller = VirtualDisplayController(service)
        controller.create(config, sink)

        controller.destroy()

        verifyOrder {
            sink.stop()
            sink.release()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `destroy before create throws`() {
        VirtualDisplayController(mockService()).destroy()
    }

    @Test(expected = IllegalStateException::class)
    fun `destroy twice throws`() {
        val controller = VirtualDisplayController(mockService())
        controller.create(config)
        controller.destroy()
        controller.destroy() // 第二次應該 throw
    }
}
