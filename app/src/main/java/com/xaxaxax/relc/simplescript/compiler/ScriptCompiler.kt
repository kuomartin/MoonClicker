package com.xaxaxax.relc.simplescript.compiler

import com.xaxaxax.relc.simplescript.domain.model.*

class ScriptCompiler {

    fun compile(script: Script): String {
        val luaCode = StringBuilder()

        // 1. Script Variables
        luaCode.append("-- ### Global Variables ###\n")
        luaCode.append("local var_last_match_x = 0\n")
        luaCode.append("local var_last_match_y = 0\n")
        for (variable in script.variables) {
            val initVal = if (variable.type == VariableType.INT) {
                variable.initialValue.toInt().toString()
            } else {
                variable.initialValue.toFloat().toString()
            }
            luaCode.append("local var_${variable.name} = $initVal\n")
        }
        luaCode.append("\n")

        // 2. Events State tracking
        luaCode.append("-- ### Events State ###\n")
        luaCode.append("local events_state = {\n")
        for (event in script.events) {
            val enabled = if (event.enabledOnStart) "true" else "false"
            luaCode.append("    [\"${event.name}\"] = { enabled = $enabled, start_tick = 0 },\n")
        }
        luaCode.append("}\n\n")

        // 3. Match Templates
        luaCode.append("-- ### Templates ###\n")
        luaCode.append("match.templates = {\n")
        for (event in script.events) {
            for (condition in event.conditions) {
                if (condition is TemplateMatchCondition) {
                    luaCode.append("    { name = \"${event.name}_${condition.hashCode()}\", target = \"${condition.imgPath}\", threshold = ${condition.threshold} },\n")
                }
            }
        }
        luaCode.append("}\n\n")

        // 4. Action Compiling logic
        luaCode.append("-- ### Actions Implementation ###\n")
        for (event in script.events) {
            luaCode.append("local function execute_actions_${event.name.replace(" ", "_")}()\n")
            luaCode.append("    log(\"Executing actions for event: ${event.name}\")\n")
            for (action in event.actions) {
                luaCode.append("    ${compileAction(action)}\n")
            }
            luaCode.append("end\n\n")
        }


        // 5. on_tick (Central Logic)
        luaCode.append("-- ### on_tick Logic ###\n")
        luaCode.append("function on_tick(matches, tick)\n")
        for (event in script.events) {
            luaCode.append("    if events_state[\"${event.name}\"].enabled then\n")

            val conditionStrings = event.conditions.map { condition ->
                when (condition) {
                    is TemplateMatchCondition -> {
                        val templateName = "${event.name}_${condition.hashCode()}"
                        "(matches[\"$templateName\"] and matches[\"$templateName\"].found)"
                    }
                    is VariableCondition -> {
                        "(var_${condition.variableA} ${condition.operator.symbol} ${formatValue(condition.target)})"
                    }
                    is TimerCondition -> {
                        val durationTicks = when (condition.unit) {
                            TimeUnit.MS -> (condition.duration / (1000f / script.fps)).toInt()
                            TimeUnit.FRAME -> condition.duration.toInt()
                            TimeUnit.S -> (condition.duration * script.fps).toInt()
                            TimeUnit.M -> (condition.duration * 60f * script.fps).toInt()
                            TimeUnit.H -> (condition.duration * 3600f * script.fps).toInt()
                        }
                        "(tick - events_state[\"${event.name}\"].start_tick >= $durationTicks)"
                    }
                }
            }

            if (conditionStrings.isNotEmpty()) {
                val logicalOp = if (event.conditionOperator == LogicalOperator.AND) " and " else " or "
                val checkConditionStr = conditionStrings.joinToString(logicalOp)

                luaCode.append("        if $checkConditionStr then\n")

                // If it was a template match, update the global last_match vars
                val templateConditions = event.conditions.filterIsInstance<TemplateMatchCondition>()
                if (templateConditions.isNotEmpty()) {
                    for (condition in templateConditions) {
                        val templateName = "${event.name}_${condition.hashCode()}"
                        luaCode.append("            if matches[\"$templateName\"] and matches[\"$templateName\"].found then\n")
                        luaCode.append("                var_last_match_x = matches[\"$templateName\"].x\n")
                        luaCode.append("                var_last_match_y = matches[\"$templateName\"].y\n")
                        luaCode.append("            end\n")
                    }
                }

                luaCode.append("            execute_actions_${event.name.replace(" ", "_")}()\n")
                luaCode.append("        end\n")
            }

            luaCode.append("    end\n")
        }
        luaCode.append("end\n\n")

        // 6. on_start
        luaCode.append("-- ### on_start ###\n")
        luaCode.append("function on_start()\n")
        luaCode.append("    log(\"Script '${script.name}' started.\")\n")
        luaCode.append("end\n")

        return luaCode.toString()
    }

    private fun compileAction(action: Action): String {
        return when (action) {
            is ClickAction -> {
                "input.swipe(-1, {${formatValue(action.point.x)}, ${formatValue(action.point.y)}}, ${action.duration})"
            }
            is SwipeAction -> {
                "input.swipe(-1, {${formatValue(action.point1.x)}, ${formatValue(action.point1.y)}, ${formatValue(action.point2.x)}, ${formatValue(action.point2.y)}}, ${action.duration})"
            }
            is WaitAction -> {
                val durationMs = when (action.unit) {
                    TimeUnit.MS -> action.duration
                    TimeUnit.FRAME -> action.duration * (1000 / 15)
                    TimeUnit.S -> action.duration * 1000
                    TimeUnit.M -> action.duration * 60000
                    TimeUnit.H -> action.duration * 3600000
                }
                "os.execute(\"sleep \" .. (${durationMs} / 1000.0))"
            }
            is SetVariableAction -> {
                if (action.operator == AssignmentOperator.ASSIGN) {
                    "var_${action.name} = ${formatValue(action.value)}"
                } else {
                    val mathOp = action.operator.symbol.replace("=", "")
                    "var_${action.name} = var_${action.name} $mathOp ${formatValue(action.value)}"
                }
            }
            is SetEventAction -> {
                when (action.operator) {
                    EventOperator.ON -> "events_state[\"${action.eventName}\"].enabled = true"
                    EventOperator.OFF -> "events_state[\"${action.eventName}\"].enabled = false"
                    EventOperator.RESET -> "events_state[\"${action.eventName}\"].start_tick = tick" // RESET should reset to current tick
                }
            }
            is SystemBtnAction -> {
                "log(\"System action ${action.op.name} not implemented in dummy compiler yet\")"
            }
            is ForLoopAction -> {
                val sb = StringBuilder()
                sb.append("for var_${action.variableName} = ${formatValue(action.from)}, ${formatValue(action.to)} do\n")
                for (subAction in action.actions) {
                    sb.append("        ${compileAction(subAction)}\n")
                }
                sb.append("    end")
                sb.toString()
            }
        }
    }

    private fun formatValue(value: String): String {
        return if (value.matches(Regex("^[a-zA-Z_].*"))) {
            "var_$value"
        } else {
            value
        }
    }
}
