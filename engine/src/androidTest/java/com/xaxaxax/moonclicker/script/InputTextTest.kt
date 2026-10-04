package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.script.puppet.PuppetRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `input.text` 打出來的字，真的送到虛擬顯示上有焦點的那個 app，而且大小寫、符號、Enter 都對。 */
@RunWith(AndroidJUnit4::class)
class InputTextTest {

    @get:Rule
    val env = Tier1Env()

    @Test
    fun text_reaches_the_focused_app_on_the_target_display() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        val text = "Hello, World!\n"

        val outcome = LuaScriptRunner(service = env.service, displayId = displayId).use {
            it.run("""data.set("ok", input.text("Hello, World!\n"))""")
        }

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        assertEquals(true, outcome.data["ok"])
        assertNotNull(
            "puppet typed \"${PuppetRecorder.current.typed}\"",
            PuppetRecorder.await(5_000) { it.typed == text },
        )
    }
}
