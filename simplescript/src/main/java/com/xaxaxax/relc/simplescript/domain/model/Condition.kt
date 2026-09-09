package com.xaxaxax.relc.simplescript.domain.model

import android.graphics.Rect

sealed class Condition

data class TemplateMatchCondition(
    val imgPath: String,
    val mask: Rect? = null,
    val threshold: Float = 0.9f,
    val grayscale: Boolean = true,
    val scale: Float = 0.5f
) : Condition()

enum class CompareOperator(val symbol: String) {
    EQUAL("=="),
    NOT_EQUAL("~="),
    GREATER_THAN(">"),
    LESS_THAN("<"),
    GREATER_OR_EQUAL(">="),
    LESS_OR_EQUAL("<=")
}

data class VariableCondition(
    val variableA: String,
    val operator: CompareOperator,
    val target: String // Can be a number string or another variable name
) : Condition()

enum class TimeUnit {
    MS, FRAME, S, M, H
}

data class TimerCondition(
    val duration: Float,
    val unit: TimeUnit
) : Condition()
