package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.lua.LuaNative
import com.xaxaxax.relc.script.ScriptConfig
import kotlinx.coroutines.delay
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

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

        LuaNative.startEngineWithService(v2Service, -1, width, height, scriptFile.absolutePath)
        try {
            while (ctx.isActive() && LuaNative.isEngineRunning()) {
                delay(500.milliseconds)
            }
        } finally {
            LuaNative.stop()
        }
    }
}

class NativeLuaScriptRunnerFactory : ScriptRunnerFactory {
    override fun create(ctx: ScriptRunContext): ScriptRunner = NativeLuaScriptRunner(ctx)
}
