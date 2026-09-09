package com.xaxaxax.relc.simplescript.domain.model

enum class VariableType {
    INT, FLOAT
}

data class Variable(
    val name: String,
    val type: VariableType,
    val initialValue: Number
)
