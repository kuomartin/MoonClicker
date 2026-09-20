package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `screen.*` 與「這個目標有沒有影格」的分界。
 *
 * 影格已經是邏輯空間（distributor 在源頭把 v 轉正，見 ADR-0017），`screen.width`／
 * `screen.height`／`screen.rotation` 是啟動當下的快照，整場執行固定不變——不像舊版
 * 會隨顯示器中途旋轉即時更新，這裡不需要真的顯示器就能驗。
 */
@RunWith(AndroidJUnit4::class)
class LuaScreenApiTest {

    @Test
    fun screen_reports_the_surface_size_when_the_display_is_unrotated() {
        LuaScriptRunner(RecordingMoonClickerService(surfaceSize = intArrayOf(1080, 1920))).use { runner ->
            val outcome = runner.run(
                """
                data.set("w", screen.width)
                data.set("h", screen.height)
                """.trimIndent()
            )

            assertEquals(1080.0, outcome.data["w"])
            assertEquals(1920.0, outcome.data["h"])
        }
    }

    // screen.width/height 隨旋轉互換長寬的覆蓋率在 Tier 1（Tier1SpikeTest，真的顯示器、真的
    // WindowManager 旋轉）——RecordingMoonClickerService 是純 Kotlin 假服務，不實作
    // MoonClickerService.getDisplaySurfaceSize 依目前 rotation 互換長寬那段邏輯（ADR-0017 的
    // B），這裡量不出任何東西。screen.rotation 也不再能從腳本執行緒外中途改變（見
    // VisionMatcher 的建構子：整場執行固定），舊版靠 nativeSetDisplayRotation 從外部注入
    // 的測試手法已經沒有對應的生產路徑，一併移除。

    @Test
    fun has_vision_is_false_on_a_target_without_a_frame_source() = withRunner { runner ->
        val outcome = runner.run("data.set('has_vision', screen.has_vision)")

        assertEquals(false, outcome.data["has_vision"])
    }

    /**
     * 沒有影格來源時 `vision.*` 要**明確報錯**，不是靜靜地永遠找不到——後者會讓使用者
     * 以為是門檻或模板的問題，然後除錯半天。
     */
    @Test
    fun vision_raises_a_self_explaining_error_when_there_are_no_frames() = withRunner { runner ->
        val outcome = runner.run("vision.find('anything.png')")

        val error = outcome.error
        assertNotNull("expected Error, got ${outcome.runState}", error)
        assertTrue("unhelpful message: $error", error!!.contains("vision is unavailable"))
        assertTrue("does not say what to do instead: $error", error.contains("virtual display"))
    }

    @Test
    fun an_unknown_screen_field_is_nil_rather_than_an_error() = withRunner { runner ->
        val outcome = runner.run("data.set('missing', screen.nope == nil)")

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(true, outcome.data["missing"])
    }

    @Test
    fun input_on_physical_display_requires_active_mirror() {
        val service = RecordingMoonClickerService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run("input.tap(100, 100)")
            val error = outcome.error
            assertNotNull("expected Error, got ${outcome.runState}", error)
            assertTrue("unexpected message: $error", error!!.contains("requires active mirror"))
        }
    }

    @Test
    fun vision_on_physical_display_requires_active_mirror() {
        val service = RecordingMoonClickerService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run("vision.find('anything.png')")
            val error = outcome.error
            assertNotNull("expected Error, got ${outcome.runState}", error)
            assertTrue("unexpected message: $error", error!!.contains("requires active mirror"))
        }
    }

    @Test
    fun screen_start_mirror_and_stop_mirror_work() {
        val service = RecordingMoonClickerService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run(
                """
                data.set('initial_mirror', screen.is_mirror_active)
                local started = screen.start_mirror()
                data.set('started', started)
                data.set('after_start_mirror', screen.is_mirror_active)
                local stopped = screen.stop_mirror()
                data.set('stopped', stopped)
                data.set('after_stop_mirror', screen.is_mirror_active)
                """.trimIndent()
            )
            assertEquals(EngineRunState.Finished, outcome.runState)
            assertEquals(false, outcome.data["initial_mirror"])
            assertEquals(true, outcome.data["started"])
            assertEquals(true, outcome.data["after_start_mirror"])
            assertEquals(true, outcome.data["stopped"])
            assertEquals(false, outcome.data["after_stop_mirror"])
        }
    }
}
