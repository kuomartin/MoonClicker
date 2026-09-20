package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `log(...)` 除了寫 logcat，每一行還要並行送進 [com.xaxaxax.moonclicker.engine.ScriptEngine.logLines]。
 */
@RunWith(AndroidJUnit4::class)
class LuaLogStreamTest {

    @Test
    fun log_lines_are_tab_joined_and_non_strings_are_tostring_converted() = withRunner { runner ->
        val outcome = runner.run(
            """
            log("a", 1, true)
            data.set("done", true)
            """.trimIndent()
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(listOf("a\t1\ttrue"), outcome.logLines)
        assertEquals(true, outcome.data["done"])
    }
}
