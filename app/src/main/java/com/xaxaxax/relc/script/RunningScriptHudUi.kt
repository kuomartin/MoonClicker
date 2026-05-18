package com.xaxaxax.relc.script

data class SimpleScriptProgress(
    val logicalStepIndex: Int,
    val logicalStepTotal: Int,
    val macroIteration: Int,
    val macroTotal: Int?,
)

data class RunningScriptHudUi(
    val scriptId: String,
    val name: String,
    val codeType: ScriptConfig.ScriptCodeType,
    val state: ScriptState,
    val progress: SimpleScriptProgress?,
    val sessionStartElapsedRealtime: Long,
)
