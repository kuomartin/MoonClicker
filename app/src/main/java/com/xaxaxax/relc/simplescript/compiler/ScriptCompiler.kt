package com.xaxaxax.relc.simplescript.compiler

import com.xaxaxax.relc.simplescript.domain.model.*

class ScriptCompiler {

    fun compile(script: Script): String {
        val luaCode = StringBuilder()

        // 1. Script Variables
        luaCode.append("-- ### Global Variables ###\n")
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
            luaCode.append("    [\"${event.name}\"] = { enabled = $enabled, timer = 0.0 },\n")
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

        // 4. Action Compiling logic (Forward declaration for events)
        luaCode.append("-- ### Actions Implementation ###\n")
        for (event in script.events) {
            luaCode.append("local function execute_actions_${event.name.replace(" ", "_")}()\n")
            luaCode.append("    log(\"Executing actions for event: ${event.name}\")\n")
            for (action in event.actions) {
                luaCode.append("    ${compileAction(action)}\n")
            }
            luaCode.append("end\n\n")
        }


        // 5. on_tick (For timers and variable conditions)
        luaCode.append("-- ### on_tick Logic ###\n")
        luaCode.append("function on_tick(dt)\n")
        for (event in script.events) {
            // Only process if enabled and it has non-template conditions
            val hasNonTemplateConditions = event.conditions.any { it !is TemplateMatchCondition }

            luaCode.append("    if events_state[\"${event.name}\"].enabled then\n")
            luaCode.append("        events_state[\"${event.name}\"].timer = events_state[\"${event.name}\"].timer + dt\n")

            if (hasNonTemplateConditions) {
                val conditionChecks = event.conditions.filter { it !is TemplateMatchCondition }.map { compileCondition(it, event.name) }
                if (conditionChecks.isNotEmpty()) {
                    val logicalOp = if (event.conditionOperator == LogicalOperator.AND) " and " else " or "
                    val checkConditionStr = conditionChecks.joinToString(logicalOp)

                    luaCode.append("        if $checkConditionStr then\n")
                    // If it also requires template match, we can't execute here yet. This might need state machine if mixed.
                    // For now, assume if it has mixed, AND means we need all, OR means we can execute if one triggers.
                    // SimpleScript usually separates them. Let's assume if it triggers here, it executes.
                    val requiresTemplate = event.conditions.any { it is TemplateMatchCondition }
                    if (!requiresTemplate || event.conditionOperator == LogicalOperator.OR) {
                        luaCode.append("            execute_actions_${event.name.replace(" ", "_")}()\n")
                    }
                    luaCode.append("        end\n")
                }
            }
            luaCode.append("    end\n")
        }
        luaCode.append("end\n\n")

        // 6. on_match (For template match conditions)
        luaCode.append("-- ### on_match Logic ###\n")
        luaCode.append("function on_match(name, results)\n")
        luaCode.append("    local triggered_event = \"\"\n")
        for (event in script.events) {
             val templateConditions = event.conditions.filterIsInstance<TemplateMatchCondition>()
             if (templateConditions.isNotEmpty()) {
                 for (condition in templateConditions) {
                     val templateName = "${event.name}_${condition.hashCode()}"
                     luaCode.append("    if events_state[\"${event.name}\"].enabled and name == \"$templateName\" then\n")
                     luaCode.append("        triggered_event = \"${event.name}\"\n")
                     luaCode.append("        local match_res = results[\"$templateName\"]\n")
                     luaCode.append("        -- Save match position to global vars for point references if needed\n")
                     luaCode.append("        var_last_match_x = match_res.x\n")
                     luaCode.append("        var_last_match_y = match_res.y\n")

                     // Check if other conditions are met if AND is used
                     val otherConditions = event.conditions.filter { it !is TemplateMatchCondition }.map { compileCondition(it, event.name) }
                     if (otherConditions.isNotEmpty() && event.conditionOperator == LogicalOperator.AND) {
                         val checkConditionStr = otherConditions.joinToString(" and ")
                         luaCode.append("        if $checkConditionStr then\n")
                         luaCode.append("            execute_actions_${event.name.replace(" ", "_")}()\n")
                         luaCode.append("        end\n")
                     } else {
                         luaCode.append("        execute_actions_${event.name.replace(" ", "_")}()\n")
                     }

                     luaCode.append("    end\n")
                 }
             }
        }
        luaCode.append("end\n\n")

        // 7. on_start
        luaCode.append("-- ### on_start ###\n")
        luaCode.append("function on_start()\n")
        luaCode.append("    log(\"Script '${script.name}' started.\")\n")
        luaCode.append("end\n")

        return luaCode.toString()
    }

    private fun compileCondition(condition: Condition, eventName: String): String {
        return when (condition) {
            is TemplateMatchCondition -> {
                // Handled in on_match
                "true"
            }
            is VariableCondition -> {
                "var_${condition.variableA} ${condition.operator.symbol} ${formatValue(condition.target)}"
            }
            is TimerCondition -> {
                val durationS = when (condition.unit) {
                    TimeUnit.MS -> condition.duration / 1000f
                    TimeUnit.FRAME -> condition.duration / 15f // Assuming 15fps as default
                    TimeUnit.S -> condition.duration
                    TimeUnit.M -> condition.duration * 60f
                    TimeUnit.H -> condition.duration * 3600f
                }
                "events_state[\"$eventName\"].timer >= $durationS"
            }
        }
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
                // Not perfectly supported by standard sleep in lua in same thread if it blocks on_tick,
                // but usually os.execute sleep or a busy loop might be used.
                // Assuming a sleep function is available or will block execution intentionally.
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
                    EventOperator.RESET -> "events_state[\"${action.eventName}\"].timer = 0.0"
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
        // If it starts with a letter, assume it's a variable reference
        return if (value.matches(Regex("^[a-zA-Z_].*"))) {
            "var_$value"
        } else {
            value
        }
    }
}
