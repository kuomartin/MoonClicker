package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.script.ScriptConfig

fun interface ScriptRunnerFactory {
    fun create(ctx: ScriptRunContext): ScriptRunner
}

interface ScriptRunner {
    suspend fun run(config: ScriptConfig)
}

data class ScriptRunContext(
    val service: IRelcShizukuService,
    val onLog: (String) -> Unit,
    val isActive: () -> Boolean,
)
