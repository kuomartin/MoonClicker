package com.xaxaxax.relc.script

data class SimpleScriptProgress(
    val logicalStepIndex: Int,
    val logicalStepTotal: Int,
    val macroIteration: Int,
    val macroTotal: Int?,
)
