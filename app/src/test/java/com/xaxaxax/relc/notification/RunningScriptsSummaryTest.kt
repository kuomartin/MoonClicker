package com.xaxaxax.relc.notification

import com.xaxaxax.relc.script.LoopMode
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunningScriptsSummaryTest {

    private fun lua(id: String, name: String) =
        ScriptConfig.Lua(id = id, name = name, description = "", code = "")

    private fun simple(id: String, name: String) =
        ScriptConfig.Simple(
            id = id,
            name = name,
            description = "",
            steps = listOf(),
            loopMode = LoopMode.None,
        )

    @Test
    fun `no scripts running yields no summary`() {
        val summary = summarizeRunningScripts(
            scripts = listOf(lua("1", "Farm"), simple("2", "Tap")),
            states = mapOf("1" to ScriptState.IDLE, "2" to ScriptState.FINISHED),
        )

        assertNull(summary)
    }

    @Test
    fun `empty state map yields no summary`() {
        assertNull(summarizeRunningScripts(scripts = listOf(lua("1", "Farm")), states = emptyMap()))
    }

    @Test
    fun `single running script is named in the summary`() {
        val summary = summarizeRunningScripts(
            scripts = listOf(lua("1", "Farm"), simple("2", "Tap")),
            states = mapOf("1" to ScriptState.RUNNING, "2" to ScriptState.IDLE),
        )

        assertEquals(RunningScriptsSummary(runningCount = 1, text = "Farm"), summary)
    }

    @Test
    fun `multiple running scripts are listed with a count`() {
        val summary = summarizeRunningScripts(
            scripts = listOf(lua("1", "Farm"), simple("2", "Tap"), lua("3", "Idle")),
            states = mapOf(
                "1" to ScriptState.RUNNING,
                "2" to ScriptState.RUNNING,
                "3" to ScriptState.IDLE,
            ),
        )

        assertEquals(RunningScriptsSummary(runningCount = 2, text = "Farm、Tap"), summary)
    }

    @Test
    fun `running scripts follow the order of the script list`() {
        val summary = summarizeRunningScripts(
            scripts = listOf(lua("3", "C"), lua("1", "A"), lua("2", "B")),
            states = mapOf(
                "1" to ScriptState.RUNNING,
                "2" to ScriptState.RUNNING,
                "3" to ScriptState.RUNNING,
            ),
        )

        assertEquals(RunningScriptsSummary(runningCount = 3, text = "C、A、B"), summary)
    }

    @Test
    fun `long name lists are truncated so the notification stays readable`() {
        val scripts = (1..10).map { lua(it.toString(), "Script $it") }
        val states = scripts.associate { it.id to ScriptState.RUNNING }

        val summary = summarizeRunningScripts(scripts, states)

        assertEquals(
            RunningScriptsSummary(
                runningCount = 10,
                text = "Script 1、Script 2、Script 3 及其他 7 個",
            ),
            summary,
        )
    }

    @Test
    fun `a running script missing from the list still counts`() {
        val summary = summarizeRunningScripts(
            scripts = listOf(lua("1", "Farm")),
            states = mapOf("1" to ScriptState.RUNNING, "ghost" to ScriptState.RUNNING),
        )

        assertEquals(RunningScriptsSummary(runningCount = 2, text = "Farm、ghost"), summary)
    }
}
