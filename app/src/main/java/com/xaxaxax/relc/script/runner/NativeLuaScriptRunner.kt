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
        // 此路徑以 displayId = -1 啟動，沒有對應的虛擬顯示，故影格尺寸取自 display 0。
        // 有虛擬顯示的路徑（FullscreenDisplayActivity）必須改傳其 surface 尺寸——見 #19。
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
