package com.xaxaxax.relc.simplescript.domain.model

sealed class Action(val actionName: String = "")

data class PointConfig(
    val x: String, // String to allow for variable references or numbers
    val y: String
)

data class ClickAction(
    val point: PointConfig,
    val duration: Long
) : Action("Click")

data class SwipeAction(
    val point1: PointConfig,
    val point2: PointConfig,
    val duration: Long
) : Action("Swipe")

data class WaitAction(
    val duration: Long,
    val unit: TimeUnit
) : Action("Wait")

enum class AssignmentOperator(val symbol: String) {
    ASSIGN("="),
    ADD_ASSIGN("+="),
    SUB_ASSIGN("-="),
    MUL_ASSIGN("*="),
    DIV_ASSIGN("/=")
}

data class SetVariableAction(
    val name: String,
    val operator: AssignmentOperator,
    val value: String
) : Action("Set Variable")

enum class EventOperator {
    ON, OFF, RESET
}

data class SetEventAction(
    val eventName: String,
    val operator: EventOperator
) : Action("Set Event")

enum class SystemOperation {
    BACK, HOME, RECENT
}

data class SystemBtnAction(
    val op: SystemOperation
) : Action("System Button")

data class ForLoopAction(
    val variableName: String,
    val from: String,
    val to: String,
    val actions: List<Action>
) : Action("For Loop")
