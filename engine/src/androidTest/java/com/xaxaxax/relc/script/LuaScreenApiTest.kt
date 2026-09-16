package com.xaxaxax.relc.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.relc.engine.state.EngineRunState
import com.xaxaxax.relc.lua.LuaNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `screen.*` 與「這個目標有沒有影格」的分界。
 *
 * 旋轉那一段是這個 repo 反覆出錯的地方（見 CONTEXT.md 的「Surface 空間 / 邏輯空間」）：
 * 影格是 surface 空間、對外座標是邏輯空間，兩者差一個直角。這裡不需要真的
 * 顯示器就能驗——native 端的換算只吃「建立時的 surface 尺寸」與「當前 rotation」兩個數字。
 */
@RunWith(AndroidJUnit4::class)
class LuaScreenApiTest {

    @Test
    fun screen_reports_the_surface_size_when_the_display_is_unrotated() {
        LuaScriptRunner(RecordingRelcService(surfaceSize = intArrayOf(1080, 1920))).use { runner ->
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

    /**
     * 轉 90 度時邏輯長寬互換，但 surface 尺寸不變。腳本只看得到邏輯空間。
     *
     * 腳本輪詢 `screen.rotation` 而不是睡固定時間，所以測試設定 rotation 的時機不影響結果。
     */
    @Test
    fun screen_width_and_height_swap_when_the_display_rotates() {
        LuaScriptRunner(RecordingRelcService(surfaceSize = intArrayOf(1080, 1920))).use { runner ->
            runner.start(
                """
                local tries = 0
                while screen.rotation == 0 and tries < 100 do
                    sleep(50)
                    tries = tries + 1
                end
                data.set("r", screen.rotation)
                data.set("w", screen.width)
                data.set("h", screen.height)
                """.trimIndent()
            )

            LuaNative.nativeSetDisplayRotation(1)
            val outcome = runner.await()

            assertEquals(EngineRunState.Finished, outcome.runState)
            assertEquals("the script never saw the rotation", 1.0, outcome.data["r"])
            assertEquals(1920.0, outcome.data["w"])
            assertEquals(1080.0, outcome.data["h"])
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
        val service = RecordingRelcService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run("input.tap(100, 100)")
            val error = outcome.error
            assertNotNull("expected Error, got ${outcome.runState}", error)
            assertTrue("unexpected message: $error", error!!.contains("requires active mirror"))
        }
    }

    @Test
    fun vision_on_physical_display_requires_active_mirror() {
        val service = RecordingRelcService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run("vision.find('anything.png')")
            val error = outcome.error
            assertNotNull("expected Error, got ${outcome.runState}", error)
            assertTrue("unexpected message: $error", error!!.contains("requires active mirror"))
        }
    }

    @Test
    fun screen_start_mirror_and_stop_mirror_work() {
        val service = RecordingRelcService().apply { mirrorActive = false }
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
