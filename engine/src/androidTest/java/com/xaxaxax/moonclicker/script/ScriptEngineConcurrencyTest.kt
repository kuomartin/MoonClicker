package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.ScriptStartResult
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 多個引擎實例：不同顯示器上並行、互不影響；同一個顯示器同時只跑一支。 */
@RunWith(AndroidJUnit4::class)
class ScriptEngineConcurrencyTest {

    private val waitForStop = """
        data.set("ready", true)
        sleep(60000)
        data.set("reached_the_end", true)
    """.trimIndent()

    @Test
    fun stopping_one_instance_leaves_the_other_running() =
        LuaScriptRunner(displayId = 0).use { first ->
            LuaScriptRunner(displayId = 1).use { second ->
                first.start(waitForStop)
                second.start(
                    """
                    data.set("ready", true)
                    local waited = 0
                    while waited < 1000 do sleep(100); waited = waited + 100 end
                    data.set("done", true)
                    """.trimIndent()
                )
                first.awaitData("ready")
                second.awaitData("ready")

                first.stop()
                val stopped = first.await()
                val finished = second.await()

                assertEquals(EngineRunState.Stopped, stopped.runState)
                assertEquals(EngineRunState.Finished, finished.runState)
                assertEquals(true, finished.data["done"])
            }
        }

    @Test
    fun an_error_in_one_instance_leaves_the_other_running() =
        LuaScriptRunner(displayId = 0).use { healthy ->
            LuaScriptRunner(displayId = 1).use { failing ->
                healthy.start(waitForStop)
                healthy.awaitData("ready")

                val failed = failing.run("error('boom')")

                assertNotNull("expected Error, got ${failed.runState}", failed.error)
                healthy.stop()
                assertEquals(EngineRunState.Stopped, healthy.await().runState)
            }
        }

    @Test
    fun a_busy_display_reports_the_script_occupying_it() =
        LuaScriptRunner(displayId = 0).use { occupant ->
            LuaScriptRunner(displayId = 0).use { intruder ->
                occupant.start(waitForStop)
                occupant.awaitData("ready")

                val result = intruder.tryStart("data.set('ran', true)")

                assertTrue("expected DisplayBusy, got $result", result is ScriptStartResult.DisplayBusy)
                assertEquals(occupant.scriptId, (result as ScriptStartResult.DisplayBusy).scriptId)
            }
        }

    @Test
    fun a_finished_script_frees_its_display() =
        LuaScriptRunner(displayId = 0).use { previous ->
            LuaScriptRunner(displayId = 0).use { next ->
                assertEquals(EngineRunState.Finished, previous.run("log('done')").runState)

                assertEquals(EngineRunState.Finished, next.run("log('again')").runState)
            }
        }
}
