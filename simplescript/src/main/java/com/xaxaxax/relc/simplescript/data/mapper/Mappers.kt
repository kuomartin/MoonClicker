package com.xaxaxax.relc.simplescript.data.mapper

import android.graphics.Rect
import com.xaxaxax.relc.simplescript.data.database.entity.*
import com.xaxaxax.relc.simplescript.domain.model.*
import org.json.JSONObject

fun ScriptEntity.toDomain(variables: List<Variable>, events: List<Event>): Script {
    return Script(
        id = id,
        name = name,
        fps = fps,
        variables = variables,
        events = events
    )
}

fun VariableEntity.toDomain(): Variable {
    return Variable(
        name = name,
        type = VariableType.valueOf(type),
        initialValue = initialValue
    )
}

fun EventEntity.toDomain(conditions: List<Condition>, actions: List<Action>): Event {
    return Event(
        name = name,
        enabledOnStart = enabledOnStart,
        conditionOperator = LogicalOperator.valueOf(conditionOperator),
        conditions = conditions,
        actions = actions
    )
}

fun ConditionEntity.toDomain(): Condition {
    return when (type) {
        "TEMPLATE_MATCH" -> TemplateMatchCondition(
            imgPath = imgPath ?: "",
            mask = null, // TODO parse mask if saved
            threshold = threshold ?: 0.9f
        )
        "VARIABLE" -> VariableCondition(
            variableA = variableA ?: "",
            operator = CompareOperator.valueOf(operator ?: "EQUAL"),
            target = target ?: ""
        )
        "TIMER" -> TimerCondition(
            duration = duration ?: 0f,
            unit = TimeUnit.valueOf(unit ?: "MS")
        )
        else -> throw IllegalArgumentException("Unknown condition type: $type")
    }
}

fun ActionEntity.toDomain(): Action {
    val json = JSONObject(paramsJson)
    return when (type) {
        "CLICK" -> ClickAction(
            point = PointConfig(json.getString("x"), json.getString("y")),
            duration = json.optLong("duration", 100)
        )
        "SWIPE" -> SwipeAction(
            point1 = PointConfig(json.getString("x1"), json.getString("y1")),
            point2 = PointConfig(json.getString("x2"), json.getString("y2")),
            duration = json.optLong("duration", 100)
        )
        "WAIT" -> WaitAction(
            duration = json.getLong("duration"),
            unit = TimeUnit.valueOf(json.optString("unit", "MS"))
        )
        "SET_VARIABLE" -> SetVariableAction(
            name = json.getString("name"),
            operator = AssignmentOperator.valueOf(json.getString("operator")),
            value = json.getString("value")
        )
        "SET_EVENT" -> SetEventAction(
            eventName = json.getString("eventName"),
            operator = EventOperator.valueOf(json.getString("operator"))
        )
        "SYSTEM_BTN" -> SystemBtnAction(
            op = SystemOperation.valueOf(json.getString("op"))
        )
        "FOR_LOOP" -> ForLoopAction(
            variableName = json.optString("variableName", "i"),
            from = json.optString("from", "1"),
            to = json.optString("to", "10"),
            actions = emptyList() // Flattened or recursive parsing needed for real support
        )
        else -> throw IllegalArgumentException("Unknown action type: $type")
    }
}

// Map Domain back to Entity
fun Script.toEntity(): ScriptEntity {
    return ScriptEntity(id = id, name = name, fps = fps)
}

fun Variable.toEntity(scriptId: Long): VariableEntity {
    return VariableEntity(
        scriptId = scriptId,
        name = name,
        type = type.name,
        initialValue = initialValue.toFloat()
    )
}

fun Event.toEntity(scriptId: Long): EventEntity {
    return EventEntity(
        scriptId = scriptId,
        name = name,
        enabledOnStart = enabledOnStart,
        conditionOperator = conditionOperator.name
    )
}

fun Condition.toEntity(eventId: Long): ConditionEntity {
    return when (this) {
        is TemplateMatchCondition -> ConditionEntity(
            eventId = eventId,
            type = "TEMPLATE_MATCH",
            imgPath = imgPath,
            threshold = threshold
        )
        is VariableCondition -> ConditionEntity(
            eventId = eventId,
            type = "VARIABLE",
            variableA = variableA,
            operator = operator.name,
            target = target
        )
        is TimerCondition -> ConditionEntity(
            eventId = eventId,
            type = "TIMER",
            duration = duration,
            unit = unit.name
        )
    }
}

fun Action.toEntity(eventId: Long, orderIndex: Int): ActionEntity {
    val json = JSONObject()
    val type = when (this) {
        is ClickAction -> {
            json.put("x", point.x)
            json.put("y", point.y)
            json.put("duration", duration)
            "CLICK"
        }
        is SwipeAction -> {
            json.put("x1", point1.x)
            json.put("y1", point1.y)
            json.put("x2", point2.x)
            json.put("y2", point2.y)
            json.put("duration", duration)
            "SWIPE"
        }
        is WaitAction -> {
            json.put("duration", duration)
            json.put("unit", unit.name)
            "WAIT"
        }
        is SetVariableAction -> {
            json.put("name", name)
            json.put("operator", operator.name)
            json.put("value", value)
            "SET_VARIABLE"
        }
        is SetEventAction -> {
            json.put("eventName", eventName)
            json.put("operator", operator.name)
            "SET_EVENT"
        }
        is SystemBtnAction -> {
            json.put("op", op.name)
            "SYSTEM_BTN"
        }
        is ForLoopAction -> {
            json.put("variableName", variableName)
            json.put("from", from)
            json.put("to", to)
            // Nested actions are complex, ideally we would recursively serialize to JSON array,
            // or flatten the DB structure to allow parent_action_id. For now let's just serialize to JSON array string.
            "FOR_LOOP"
        }
    }
    return ActionEntity(
        eventId = eventId,
        type = type,
        orderIndex = orderIndex,
        paramsJson = json.toString()
    )
}
