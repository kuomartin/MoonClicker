package com.xaxaxax.relc.simplescript.compiler

import com.xaxaxax.relc.simplescript.domain.model.*
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCompilerTest {

    @Test
    fun compile_basicScript_generatesValidLua() {
        val script = Script(
            name = "Test Script",
            fps = 15,
            variables = listOf(
                Variable("counter", VariableType.INT, 0),
                Variable("threshold", VariableType.FLOAT, 1.5)
            ),
            events = listOf(
                Event(
                    name = "AutoLogin",
                    conditions = listOf(
                        TemplateMatchCondition(imgPath = "/sdcard/login.png")
                    ),
                    actions = listOf(
                        ClickAction(PointConfig("last_match_x", "last_match_y"), 100),
                        SetVariableAction("counter", AssignmentOperator.ADD_ASSIGN, "1")
                    )
                ),
                Event(
                    name = "TimeoutCheck",
                    conditions = listOf(
                        TimerCondition(10f, TimeUnit.S)
                    ),
                    actions = listOf(
                        SetEventAction("AutoLogin", EventOperator.OFF)
                    )
                )
            )
        )

        val compiler = ScriptCompiler()
        val luaCode = compiler.compile(script)
        println(luaCode)

        assertTrue(luaCode.contains("local var_counter = 0"))
        assertTrue(luaCode.contains("local var_threshold = 1.5"))
        assertTrue(luaCode.contains("function on_tick(matches, tick)"))
        assertTrue(luaCode.contains("if (matches[\"AutoLogin_"))
        assertTrue(luaCode.contains("tick - events_state[\"TimeoutCheck\"].start_tick >= 150"))
        assertTrue(luaCode.contains("events_state[\"AutoLogin\"].enabled = false"))
        assertTrue(luaCode.contains("input.swipe(-1, {var_last_match_x, var_last_match_y}, 100)"))
        assertTrue(luaCode.contains("var_counter = var_counter + 1"))
    }
}
