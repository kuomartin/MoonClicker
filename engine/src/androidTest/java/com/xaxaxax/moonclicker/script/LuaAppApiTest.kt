package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `app.*` 對真的 system_server。 */
@RunWith(AndroidJUnit4::class)
class LuaAppApiTest {

    @get:Rule
    val env = Tier1Env()

    /** `app.list()` 列出所有顯示器上的 task，與腳本的目標顯示器無關。 */
    @Test
    fun app_list_finds_the_puppet_on_its_display_from_any_target() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        val script =
            """
            local found = 0
            for _, task in ipairs(app.list()) do
                if task.package == "${env.puppetPackage}" and task.display_id == $displayId then
                    found = found + 1
                end
            end
            data.set("found", found)
            """.trimIndent()

        for (target in listOf(displayId, 0)) {
            val outcome = LuaScriptRunner(service = env.service, displayId = target).use { it.run(script) }

            assertEquals("target $target: ${outcome.error}", EngineRunState.Finished, outcome.runState)
            assertEquals(
                "target $target: expected exactly one (${env.puppetPackage}, $displayId) entry",
                1.0,
                outcome.data["found"],
            )
        }
    }

    /** package 打錯是腳本能處理的情況：回 false，不是 Lua error（ADR-0021）。 */
    @Test
    fun app_launch_of_a_package_that_is_not_installed_returns_false() {
        val displayId = env.createDisplay()
        val outcome = LuaScriptRunner(service = env.service, displayId = displayId).use {
            it.run("data.set('ok', app.launch('com.xaxaxax.moonclicker.not.installed'))")
        }

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        assertEquals(false, outcome.data["ok"])
    }
}
