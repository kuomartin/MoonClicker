package com.xaxaxax.relc.script

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.relc.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `input.*` 送到 [com.xaxaxax.relc.IRelcV2Service] 邊界上的到底是什麼。
 *
 * 這些值——攤平後的座標順序、duration、pointer id、`keep` 旗標——是 Lua 文件對使用者的
 * 承諾，而它們經過 C++ 的參數解析與 [ScriptHost] 兩層轉換，兩層都沒有型別系統在保護。
 */
@RunWith(AndroidJUnit4::class)
class LuaInputApiTest {

    @Test
    fun tap_becomes_a_degenerate_one_point_swipe() = withRunner { runner ->
        val outcome = runner.run("input.tap(120, 340)")

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(
            listOf(RecordingRelcService.Swipe(-1, 0, listOf(120, 340, 120, 340), 50L, false)),
            runner.recorded.swipes,
        )
    }

    @Test
    fun tap_hold_overrides_the_default_duration() = withRunner { runner ->
        runner.run("input.tap(1, 2, 250)")

        assertEquals(250L, runner.recorded.swipes.single().durationMs)
    }

    /** 文件承諾兩種寫法等價，所以它們必須送出同一串座標。 */
    @Test
    fun swipe_accepts_flat_and_nested_point_lists() {
        val flat = LuaScriptRunner().use {
            it.run("input.swipe({10, 20, 30, 40})")
            it.recorded.swipes.single().points
        }
        val nested = LuaScriptRunner().use {
            it.run("input.swipe({{10, 20}, {30, 40}})")
            it.recorded.swipes.single().points
        }

        assertEquals(listOf(10, 20, 30, 40), flat)
        assertEquals(flat, nested)
    }

    @Test
    fun swipe_rejects_an_odd_number_of_coordinates() = withRunner { runner ->
        val outcome = runner.run("input.swipe({1, 2, 3})")

        val error = outcome.error
        assertTrue("expected an Error, got ${outcome.runState}", error != null)
        assertTrue("unhelpful message: $error", error!!.contains("x,y pairs"))
    }

    /**
     * `input.up` 沒有座標參數——它要在**最後一次 move 的位置**放開，所以 [ScriptHost]
     * 必須把 down/move 的座標記住。記錯的話手指會在畫面上瞬移之後才放開。
     */
    @Test
    fun up_releases_at_the_last_move_position() = withRunner { runner ->
        runner.run(
            """
            input.down(1, 5, 6)
            input.move(1, 7, 8)
            input.up(1)
            """.trimIndent()
        )

        assertEquals(
            listOf(
                RecordingRelcService.Swipe(1, 0, listOf(5, 6), 0L, true),
                RecordingRelcService.Swipe(1, 0, listOf(7, 8), 0L, true),
                RecordingRelcService.Swipe(1, 0, listOf(7, 8), 0L, false),
            ),
            runner.recorded.swipes,
        )
    }

    /**
     * 忘了 `input.up` 時引擎會在收尾時補上——不然目標 app 會一直以為手指還按著。
     * 補放開發生在 [com.xaxaxax.relc.engine.ScriptEngine.stop]，也就是 [LuaScriptRunner.close]。
     */
    @Test
    fun a_pointer_left_down_is_released_when_the_run_is_torn_down() {
        val runner = LuaScriptRunner()
        runner.run("input.down(3, 11, 22)")
        assertEquals(1, runner.recorded.swipes.size)

        runner.close()

        assertEquals(
            RecordingRelcService.Swipe(3, 0, listOf(11, 22), 0L, false),
            runner.recorded.swipes.last(),
        )
    }

    @Test
    fun multi_swipe_dispatches_every_pointer() = withRunner { runner ->
        runner.run("input.multi_swipe({ [1] = {0, 0, 10, 10}, [2] = {50, 50, 60, 60} }, 100)")

        val byPointer = runner.recorded.swipes.associateBy { it.pointerId }
        assertEquals(setOf(1, 2), byPointer.keys)
        assertEquals(listOf(0, 0, 10, 10), byPointer.getValue(1).points)
        assertEquals(listOf(50, 50, 60, 60), byPointer.getValue(2).points)
        assertTrue(byPointer.values.all { it.durationMs == 100L })
    }

    @Test
    fun the_key_shortcuts_map_to_the_documented_keycodes() = withRunner { runner ->
        runner.run(
            """
            input.back()
            input.home()
            input.recents()
            input.key(66)
            """.trimIndent()
        )

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_ENTER,
            ),
            runner.recorded.keyDowns.map { it.keyCode },
        )
    }

    @Test
    fun app_launch_targets_the_display_the_run_was_started_on() = withRunner { runner ->
        val outcome = runner.run(
            """
            local ok = app.launch("com.example.game")
            data.set("ok", ok)
            """.trimIndent()
        )

        assertEquals(
            listOf(RecordingRelcService.Launch("com.example.game", 0)),
            runner.recorded.launches,
        )
        assertEquals(true, outcome.data["ok"])
    }
}

/** 每個測試都要自己的 runner，而且一定要收掉——原生引擎是單例。 */
internal inline fun withRunner(block: (LuaScriptRunner) -> Unit) =
    LuaScriptRunner().use(block)
