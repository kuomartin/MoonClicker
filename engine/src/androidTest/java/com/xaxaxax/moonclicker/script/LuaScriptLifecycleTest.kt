package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 執行模型本身：線性腳本跑到底就結束、錯誤怎麼傳出來、停止是不是乾淨地展開
 * （[ADR-0010](../../../../../../../docs/adr/0010-linear-scripts-replace-the-tick-loop.md)）。
 */
@RunWith(AndroidJUnit4::class)
class LuaScriptLifecycleTest {

    @Test
    fun running_off_the_end_of_main_lua_means_Finished() = withRunner { runner ->
        val outcome = runner.run("log('hello')")

        assertEquals(EngineRunState.Finished, outcome.runState)
    }

    @Test
    fun a_lua_error_surfaces_with_its_message_and_a_traceback() = withRunner { runner ->
        val outcome = runner.run(
            """
            local function inner() error("boom") end
            inner()
            """.trimIndent()
        )

        val error = outcome.error
        assertNotNull("expected Error, got ${outcome.runState}", error)
        assertTrue("no message: $error", error!!.contains("boom"))
        assertTrue("no traceback: $error", error.contains("stack traceback"))
    }

    @Test
    fun a_syntax_error_fails_the_run_instead_of_starting_it() = withRunner { runner ->
        val outcome = runner.run("this is not lua")

        assertNotNull("expected Error, got ${outcome.runState}", outcome.error)
    }

    /**
     * 使用者按停止 → `Stopped`，**不是** `Error`。腳本被展開的方式是從阻塞呼叫裡拋
     * Lua 錯誤，所以這兩者在引擎內部長得一模一樣；分辨它們是引擎的對外承諾。
     */
    @Test
    fun stopping_a_sleeping_script_reports_Stopped_not_Error() = withRunner { runner ->
        runner.start(
            """
            data.set("sleeping", true)
            sleep(60000)
            data.set("reached_the_end", true)
            """.trimIndent()
        )
        // interruptibleSleep 進入前先檢查執行旗標，所以 stop 落在 data.set 與 sleep 之間也算數。
        runner.awaitData("sleeping")

        val elapsed = kotlin.system.measureTimeMillis { runner.stop() }
        val outcome = runner.await()

        assertEquals(EngineRunState.Stopped, outcome.runState)
        assertTrue("stop() took ${elapsed}ms — sleep was not interrupted", elapsed < 3_000)
        assertNull("the script kept running past the stop", outcome.data["reached_the_end"])
    }

    /** 多檔腳本：`package.path` 指向腳本資料夾，`require` 直接可用。 */
    @Test
    fun require_resolves_files_next_to_main_lua() = withRunner { runner ->
        val outcome = runner.run(
            main = """
                local helpers = require("helpers")
                data.set("answer", helpers.answer())
            """.trimIndent(),
            files = mapOf("helpers.lua" to "return { answer = function() return 42 end }"),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(42.0, outcome.data["answer"])
    }

    /** `data.set` 是腳本回報結果的管道，也是這整套測試斷言 Lua 端事實的方式。 */
    @Test
    fun data_set_boxes_each_lua_type_the_documented_way() = withRunner { runner ->
        val outcome = runner.run(
            """
            data.set("n", 1.5)
            data.set("s", "text")
            data.set("b", false)
            data.set("t", { a = 1 })
            data.set("gone", nil)
            """.trimIndent()
        )

        // table 那一行會把整份腳本帶走（cjson 不在 _G），所以終態也要一起釘住。
        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(1.5, outcome.data["n"])
        assertEquals("text", outcome.data["s"])
        assertEquals(false, outcome.data["b"])
        // table 以 JSON 字串過橋（ScriptRuntime::boxLuaValue）。
        assertEquals("{\"a\":1}", outcome.data["t"])
        assertEquals(null, outcome.data["gone"])
    }

    @Test
    fun device_notify_and_open_uri_reach_the_host() = withRunner { runner ->
        val outcome = runner.run(
            """
            device.notify("title", "body")
            device.open_uri("https://example.com")
            """.trimIndent()
        )

        assertEquals(listOf("title" to "body"), outcome.notifications)
        assertEquals(listOf("https://example.com"), outcome.openedUris)
    }

    /** `on_stop` 在腳本本體跑完之後被呼叫（`docs/lua-api.md`）。 */
    @Test
    fun on_stop_runs_after_the_script_body() = withRunner { runner ->
        val outcome = runner.run(
            """
            function on_stop()
                data.set("cleaned_up", true)
            end
            data.set("body_done", true)
            """.trimIndent()
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(true, outcome.data["body_done"])
        assertEquals(true, outcome.data["cleaned_up"])
    }
}
