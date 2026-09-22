package com.xaxaxax.moonclicker.script

import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.MoonClickerDisplayInfo
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.core.DisplayConfig
import com.xaxaxax.moonclicker.notification.ScriptStatusNotifier
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `matchesSize` 決定沿用現有虛擬顯示的規則：跟建立尺寸精確比對，不接受長寬互換——
 * 接受的話腳本可能跑在 surface 幾何相反的顯示器上。
 */
class ScriptSessionTest {

    @Test
    fun `exact match is accepted`() {
        assertTrue(matchesSize(intArrayOf(1080, 2400), width = 1080, height = 2400))
    }

    @Test
    fun `swapped width and height is rejected`() {
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

    // ── resolveDisplay：用 `DisplayConfig.name`（腳本的 uniqueId）找、沒找到才建，
    //    找到但尺寸/densityDpi 不符就 resize 既有的那個 ──────────────────────────

    private fun session(): ScriptSession = ScriptSession(
        context = mockk(relaxed = true),
        shizukuManager = mockk(relaxed = true),
        notifier = mockk<ScriptStatusNotifier>(relaxed = true),
        settings = mockk<AppSettings>(relaxed = true),
    )

    private fun info(id: Int, name: String, width: Int, height: Int, densityDpi: Int) =
        MoonClickerDisplayInfo().apply {
            this.displayId = id
            this.name = name
            this.width = width
            this.height = height
            this.densityDpi = densityDpi
        }

    private val config = DisplayConfig(name = "daily-checkin", width = 1080, height = 2400, densityDpi = 440)

    @Test
    fun `physical display target resolves to display 0`() {
        val service = mockk<IMoonClickerService>()
        assertEquals(0, session().resolveDisplay(service, ScriptTarget.PhysicalDisplay))
    }

    @Test
    fun `existing virtual target resolves to the stored id as-is`() {
        val service = mockk<IMoonClickerService>()
        assertEquals(42, session().resolveDisplay(service, ScriptTarget.ExistingVirtual(42)))
    }

    @Test
    fun `new virtual with no name match creates a display`() {
        val service = mockk<IMoonClickerService>()
        every { service.virtualDisplays } returns intArrayOf()
        every { service.createVirtualDisplay("daily-checkin", 1080, 2400, 440, 0) } returns 7

        val result = session().resolveDisplay(service, ScriptTarget.NewVirtual(config))

        assertEquals(7, result)
    }

    @Test
    fun `new virtual reuses an existing display whose name and size already match`() {
        val service = mockk<IMoonClickerService>()
        every { service.virtualDisplays } returns intArrayOf(3)
        every { service.getDisplayInfo(3) } returns info(3, "daily-checkin", 1080, 2400, 440)
        every { service.getDisplaySurfaceSize(3) } returns intArrayOf(1080, 2400)

        val result = session().resolveDisplay(service, ScriptTarget.NewVirtual(config))

        assertEquals(3, result)
    }

    @Test
    fun `new virtual resizes an existing display whose name matches but size does not`() {
        val service = mockk<IMoonClickerService>()
        every { service.virtualDisplays } returns intArrayOf(3)
        every { service.getDisplayInfo(3) } returns info(3, "daily-checkin", 1080, 1920, 440)
        every { service.getDisplaySurfaceSize(3) } returns intArrayOf(1080, 1920)
        every { service.resizeVirtualDisplay(3, 1080, 2400, 440) } returns true

        val result = session().resolveDisplay(service, ScriptTarget.NewVirtual(config))

        assertEquals(3, result)
    }

    @Test
    fun `new virtual resizes an existing display whose name matches but densityDpi does not`() {
        val service = mockk<IMoonClickerService>()
        every { service.virtualDisplays } returns intArrayOf(3)
        every { service.getDisplayInfo(3) } returns info(3, "daily-checkin", 1080, 2400, 320)
        every { service.getDisplaySurfaceSize(3) } returns intArrayOf(1080, 2400)
        every { service.resizeVirtualDisplay(3, 1080, 2400, 440) } returns true

        val result = session().resolveDisplay(service, ScriptTarget.NewVirtual(config))

        assertEquals(3, result)
    }

    @Test
    fun `new virtual fails outright when resize fails, without falling back to recreate`() {
        val service = mockk<IMoonClickerService>()
        every { service.virtualDisplays } returns intArrayOf(3)
        every { service.getDisplayInfo(3) } returns info(3, "daily-checkin", 1080, 1920, 440)
        every { service.getDisplaySurfaceSize(3) } returns intArrayOf(1080, 1920)
        every { service.resizeVirtualDisplay(3, 1080, 2400, 440) } returns false

        val result = session().resolveDisplay(service, ScriptTarget.NewVirtual(config))

        assertNull(result)
    }

    @Test
    fun `new virtual does not reuse a same-size display belonging to a different script`() {
        val service = mockk<IMoonClickerService>()
        every { service.virtualDisplays } returns intArrayOf(3)
        every { service.getDisplayInfo(3) } returns info(3, "someone-elses-script", 1080, 2400, 440)
        every { service.createVirtualDisplay("daily-checkin", 1080, 2400, 440, 0) } returns 9

        val result = session().resolveDisplay(service, ScriptTarget.NewVirtual(config))

        assertEquals(9, result)
    }
}
