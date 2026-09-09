package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.engine.LuaEngineControl
import com.xaxaxax.relc.engine.state.EngineRunState
import com.xaxaxax.relc.engine.state.EngineStateRepository
import com.xaxaxax.relc.script.ScriptConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

class ScriptExecutionException(message: String) : Exception(message)

class NativeLuaScriptRunner(
    private val ctx: ScriptRunContext,
) : ScriptRunner {

    override suspend fun run(config: ScriptConfig) {
        val v2Service = ctx.service
        val size = v2Service.getDisplaySize(0)
        val width = size[0]
        val height = size[1]
        require(config is ScriptConfig.Lua)
        val scriptFile = File(ctx.context.filesDir, "script_${config.id}.lua")
        scriptFile.writeText(config.code)

        LuaEngineControl.startEngineWithService(v2Service, -1, width, height, scriptFile.absolutePath)
        try {
            val finalRunState = EngineStateRepository.state
                .map { it.runState }
                .first { it is EngineRunState.Finished || it is EngineRunState.Error || it is EngineRunState.Stopped }
            if (finalRunState is EngineRunState.Error) {
                throw ScriptExecutionException(finalRunState.message)
            }
        } finally {
            LuaEngineControl.stop()
        }
    }
}

class NativeLuaScriptRunnerFactory : ScriptRunnerFactory {
    override fun create(ctx: ScriptRunContext): ScriptRunner = NativeLuaScriptRunner(ctx)
}
