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
 * `screen.width`/`screen.height`/`screen.rotation` 是啟動當下的快照，整場執行固定不變，
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

    /** 腳本忘了 `stop_mirror()` 就結束時，引擎收尾要替它歸還。 */
    @Test
    fun the_mirror_this_script_started_is_released_when_it_ends() {
        val service = RecordingMoonClickerService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run("screen.start_mirror()")

            assertEquals(EngineRunState.Finished, outcome.runState)
        }

        assertEquals(1, service.releaseMirrorCalls)
        assertEquals(false, service.mirrorActive)
    }

    /** 別人開的鏡像（例如 UI 的鏡像面板）不是這支腳本的，收尾時不能關掉。 */
    @Test
    fun a_mirror_the_script_did_not_start_is_left_alone_when_it_ends() {
        val service = RecordingMoonClickerService().apply { mirrorActive = true }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run("data.set('active', screen.is_mirror_active)")

            assertEquals(EngineRunState.Finished, outcome.runState)
            assertEquals(true, outcome.data["active"])
        }

        assertEquals(0, service.releaseMirrorCalls)
        assertEquals(true, service.mirrorActive)
    }

    /** 收尾時一項失敗不能擋住後面的清理：鏡像歸還失敗，pointer 照樣放開，反過來也一樣。 */
    @Test
    fun cleanup_keeps_going_when_the_service_fails() {
        val service = RecordingMoonClickerService().apply { mirrorActive = false }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run(
                """
                screen.start_mirror()
                input.down(1, 10, 10)
                """.trimIndent()
            )
            assertEquals(EngineRunState.Finished, outcome.runState)
            service.failWith = IllegalStateException("service gone")
        }

        assertEquals(false, service.swipes.last().keep)
        assertEquals(1, service.releaseMirrorCalls)
    }
}
