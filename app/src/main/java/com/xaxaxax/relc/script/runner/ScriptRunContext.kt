package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.SimpleScriptProgress

fun interface ScriptRunnerFactory {
    fun create(ctx: ScriptRunContext): ScriptRunner
}

interface ScriptRunner {
    suspend fun run(config: ScriptConfig)
}

data class ScriptRunContext(
    val service: IRelcV2Service,
    val onLog: (String) -> Unit,
    val isActive: () -> Boolean,
    val sessionStartElapsedRealtime: Long = 0L,
    val onSimpleProgress: ((SimpleScriptProgress) -> Unit)? = null,
)
