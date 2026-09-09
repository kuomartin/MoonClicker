package com.xaxaxax.relc.simplescript.domain.model

enum class LogicalOperator {
    AND, OR
}

data class Event(
    val name: String,
    val enabledOnStart: Boolean = true,
    val conditionOperator: LogicalOperator = LogicalOperator.AND,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList()
)
