package com.xaxaxax.moonclicker.script

import android.os.DeadObjectException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 服務失敗時 Lua 看到什麼（ADR-0021）：基礎設施失敗拋 `"<module.func>: <原因>"`，
 * 操作本身沒成功回 false。
 */
@RunWith(AndroidJUnit4::class)
class LuaHostErrorTest {

    /** 每個會呼叫服務的 Lua 函式，與它在錯誤訊息裡的名字。 */
    private val callsThatReachTheService = listOf(
        "input.tap" to "input.tap(10, 10)",
        "input.swipe" to "input.swipe({10, 10, 20, 20})",
        "input.multi_swipe" to "input.multi_swipe({[1] = {10, 10, 20, 20}})",
        "input.down" to "input.down(1, 10, 10)",
        "input.move" to "pcall(input.down, 1, 10, 10) ; input.move(1, 20, 20)",
        "input.key" to "input.key(66)",
        "input.back" to "input.back()",
        "input.home" to "input.home()",
        "input.recents" to "input.recents()",
        "input.text" to "input.text('a')",
        "app.launch" to "app.launch('com.example.game')",
        "app.list" to "app.list()",
        "app.mute" to "app.mute('com.example.game', true)",
    )

    /** `DeadObjectException` 沒有 message，訊息要退而用類別名稱。 */
    @Test
    fun a_disconnected_service_raises_with_the_function_name_and_the_exception_type() {
        for ((function, script) in callsThatReachTheService) {
            // 非實體螢幕：input.* 不先問鏡像狀態，錯誤一定來自 function 本身。
            LuaScriptRunner(displayId = VIRTUAL_DISPLAY).use { runner ->
                runner.recorded.failWith = DeadObjectException()
                val outcome = runner.run(script)

                val error = outcome.error
                assertNotNull("$function: expected Error, got ${outcome.runState}", error)
                assertTrue(
                    "$function: unexpected message: $error",
                    error!!.contains("$function: android.os.DeadObjectException"),
                )
            }
        }
    }

    @Test
    fun a_service_exception_raises_with_its_message() = withVirtualRunner { runner ->
        runner.recorded.failWith = IllegalStateException("user service crashed")
        val outcome = runner.run("app.mute('com.example.game', true)")

        assertTrue("unexpected message: ${outcome.error}", outcome.error!!.contains("app.mute: user service crashed"))
    }

    @Test
    fun a_refused_key_injection_returns_false() = withVirtualRunner { runner ->
        runner.recorded.injectKeyResult = false
        val outcome = runner.run(
            """
            data.set("key", input.key(66))
            data.set("back", input.back())
            data.set("text", input.text("a"))
            """.trimIndent()
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(false, outcome.data["key"])
        assertEquals(false, outcome.data["back"])
        assertEquals(false, outcome.data["text"])
    }

    /** DOWN 被拒時不送 UP：服務端沒有按下的鍵，UP 只會是一個孤立的事件。 */
    @Test
    fun a_refused_key_down_is_not_followed_by_an_up() = withVirtualRunner { runner ->
        runner.recorded.injectKeyResult = false
        runner.run("input.key(66)")

        assertEquals(1, runner.recorded.keys.size)
    }

    @Test
    fun a_mirror_that_cannot_be_acquired_raises() {
        val service = RecordingMoonClickerService().apply {
            mirrorActive = false
            acquireMirrorResult = false
        }
        LuaScriptRunner(service = service, displayId = 0).use { runner ->
            val outcome = runner.run("screen.start_mirror()")

            val error = outcome.error
            assertNotNull("expected Error, got ${outcome.runState}", error)
            assertTrue("unexpected message: $error", error!!.contains("screen.start_mirror: could not acquire"))
        }
    }

    /**
     * pcall 接住的失敗不能留下 pending exception：之後的 JNI 呼叫（這裡是 data.set）在 CheckJNI
     * 下會直接 abort。跑很多圈也確認拋錯時 local ref 有釋放。
     */
    @Test
    fun a_caught_failure_leaves_the_engine_usable() = withVirtualRunner { runner ->
        runner.recorded.failWith = DeadObjectException()
        val outcome = runner.run(
            """
            local caught = 0
            local last
            for i = 1, 2000 do
              local ok, err = pcall(app.mute, "com.example.game", true)
              if not ok then caught = caught + 1; last = err end
            end
            data.set("caught", caught)
            data.set("last", last)
            """.trimIndent()
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(2000.0, outcome.data["caught"])
        assertTrue("unexpected message: ${outcome.data["last"]}", (outcome.data["last"] as String).contains("app.mute: "))
    }

    private inline fun withVirtualRunner(block: (LuaScriptRunner) -> Unit) =
        LuaScriptRunner(displayId = VIRTUAL_DISPLAY).use(block)

    private companion object {
        const val VIRTUAL_DISPLAY = 7
    }
}
