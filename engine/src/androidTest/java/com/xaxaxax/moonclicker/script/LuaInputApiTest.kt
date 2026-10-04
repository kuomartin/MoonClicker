package com.xaxaxax.moonclicker.script

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `input.*` 送到 [com.xaxaxax.moonclicker.IMoonClickerService] 邊界上的到底是什麼。
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
            listOf(RecordingMoonClickerService.Swipe(-1, 0, listOf(120, 340, 120, 340), 50L, false)),
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
                RecordingMoonClickerService.Swipe(1, 0, listOf(5, 6), 0L, true),
                RecordingMoonClickerService.Swipe(1, 0, listOf(7, 8), 0L, true),
                RecordingMoonClickerService.Swipe(1, 0, listOf(7, 8), 0L, false),
            ),
            runner.recorded.swipes,
        )
    }

    /**
     * 忘了 `input.up` 時引擎會在收尾時補上——不然目標 app 會一直以為手指還按著。
     * 補放開發生在 [com.xaxaxax.moonclicker.engine.ScriptEngine.stop]，也就是 [LuaScriptRunner.close]。
     */
    @Test
    fun a_pointer_left_down_is_released_when_the_run_is_torn_down() {
        val runner = LuaScriptRunner()
        runner.run("input.down(3, 11, 22)")
        assertEquals(1, runner.recorded.swipes.size)

        runner.close()

        assertEquals(
            RecordingMoonClickerService.Swipe(3, 0, listOf(11, 22), 0L, false),
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

    /** 把錄到的 down 事件依 VIRTUAL_KEYBOARD 還原成字元——驗的是「打出來的字」，不是事件序列的細節。 */
    private fun RecordingMoonClickerService.typedText(): String {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        return keyDowns
            .map { map.get(it.keyCode, it.metaState) }
            .filter { it != 0 }
            .joinToString("") { it.toChar().toString() }
    }

    @Test
    fun text_types_ascii_with_shift_on_the_target_display() = withRunner { runner ->
        val outcome = runner.run(
            """
            data.set("ok", input.text("Hello, World!\t\n"))
            """.trimIndent()
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(true, outcome.data["ok"])
        assertEquals("Hello, World!\t\n", runner.recorded.typedText())
        assertTrue(runner.recorded.keys.all { it.displayId == 0 })
    }

    @Test
    fun text_rejects_unsupported_characters_before_typing_anything() {
        for ((script, position) in listOf(
            "input.text(\"a中\")" to "position 2",
            "input.text(\"ab\\1\")" to "position 3",
        )) {
            LuaScriptRunner().use { runner ->
                val outcome = runner.run(script)

                val error = outcome.error
                assertTrue("$script: expected an Error, got ${outcome.runState}", error != null)
                assertTrue("$script: unhelpful message: $error", error!!.contains(position))
                assertTrue("$script: typed ${runner.recorded.keys}", runner.recorded.keys.isEmpty())
            }
        }
    }

    @Test
    fun text_of_an_empty_string_succeeds_without_events() = withRunner { runner ->
        val outcome = runner.run("""data.set("ok", input.text(""))""")

        assertEquals(true, outcome.data["ok"])
        assertTrue(runner.recorded.keys.isEmpty())
    }

    @Test
    fun text_coerces_numbers_like_other_string_parameters() = withRunner { runner ->
        runner.run("input.text(123)")

        assertEquals("123", runner.recorded.typedText())
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
            listOf(RecordingMoonClickerService.Launch("com.example.game", 0)),
            runner.recorded.launches,
        )
        assertEquals(true, outcome.data["ok"])
    }

    @Test
    fun app_list_returns_every_display_regardless_of_the_target() = withRunner { runner ->
        runner.recorded.appTasks = listOf("com.example.game" to 7, "com.android.launcher" to 0)
        val outcome = runner.run(
            """
            local apps = app.list()
            data.set("count", #apps)
            data.set("first", apps[1].package .. "@" .. apps[1].display_id)
            data.set("second", apps[2].package .. "@" .. apps[2].display_id)
            data.set("integer", math.type(apps[1].display_id))
            """.trimIndent()
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(2.0, outcome.data["count"])
        assertEquals("com.example.game@7", outcome.data["first"])
        assertEquals("com.android.launcher@0", outcome.data["second"])
        assertEquals("integer", outcome.data["integer"])
    }

    /** 空表代表「沒有 app 在跑」，所以查詢失敗不能也回空表。 */
    @Test
    fun app_list_raises_when_the_service_cannot_answer() = withRunner { runner ->
        runner.recorded.appTasks = null
        val outcome = runner.run("app.list()")

        val error = outcome.error
        assertTrue("expected an Error, got ${outcome.runState}", error != null)
        assertTrue("unhelpful message: $error", error!!.contains("app.list"))
    }

    @Test
    fun app_mute_passes_package_and_flag_and_returns_the_result() = withRunner { runner ->
        runner.recorded.muteResult = false
        val outcome = runner.run(
            """
            data.set("on", app.mute("com.example.game", true))
            data.set("off", app.mute("com.example.game", false))
            """.trimIndent()
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(
            listOf(
                RecordingMoonClickerService.Mute("com.example.game", true),
                RecordingMoonClickerService.Mute("com.example.game", false),
            ),
            runner.recorded.mutes,
        )
        assertEquals(false, outcome.data["on"])
        assertEquals(false, outcome.data["off"])
    }

    /** 省略 muted 不能被當成靜音：狀態活得比腳本久，打錯字時很難察覺。 */
    @Test
    fun app_mute_requires_a_boolean_flag() = withRunner { runner ->
        val outcome = runner.run("app.mute(\"com.example.game\")")

        assertTrue("expected an Error, got ${outcome.runState}", outcome.error != null)
        assertTrue(runner.recorded.mutes.isEmpty())
    }
}
