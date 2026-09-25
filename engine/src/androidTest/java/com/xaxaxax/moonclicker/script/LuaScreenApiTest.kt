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
 * `screen.width`／`screen.height`／`screen.rotation` 是啟動當下的快照，整場執行固定不變，
 * 所以不需要真的顯示器就能驗。隨旋轉互換長寬的部分在 Tier 1 的 `VisionCoordinatesTest`。
 */
@RunWith(AndroidJUnit4::class)
class LuaScreenApiTest {

    @Test
    fun screen_reports_the_display_size() {
        LuaScriptRunner(RecordingMoonClickerService(size = intArrayOf(1080, 1920))).use { runner ->
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
    fun start_mirror_activates_it() {
        val service = RecordingMoonClickerService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run(
                """
                data.set("before", screen.is_mirror_active)
                data.set("started", screen.start_mirror())
                data.set("after", screen.is_mirror_active)
                """.trimIndent()
            )

            assertEquals(EngineRunState.Finished, outcome.runState)
            assertEquals(false, outcome.data["before"])
            assertEquals(true, outcome.data["started"])
            assertEquals(true, outcome.data["after"])
        }
    }

    /** 只釋放這支腳本自己 `start_mirror` 取得的鏡像。 */
    @Test
    fun stop_mirror_releases_the_mirror_this_script_started() {
        val service = RecordingMoonClickerService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run(
                """
                screen.start_mirror()
                data.set("stopped", screen.stop_mirror())
                data.set("after", screen.is_mirror_active)
                """.trimIndent()
            )

            assertEquals(EngineRunState.Finished, outcome.runState)
            assertEquals(true, outcome.data["stopped"])
            assertEquals(false, outcome.data["after"])
        }
    }
}
