package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.lua.LuaNative
import com.xaxaxax.relc.script.ScriptConfig
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class NativeLuaScriptRunner(
    private val ctx: ScriptRunContext,
) : ScriptRunner {

    val luaNative = LuaNative()

    override suspend fun run(config: ScriptConfig) {
        val v2Service = ctx.service
        val size = v2Service.getDisplaySize(0)
        val width = size[0]
        val height = size[1]

        luaNative.startEngineWithService(v2Service, width, height, config.code)
        try {
            while (ctx.isActive() && luaNative.isEngineRunning()) {
                delay(500.milliseconds)
            }
        } finally {
            luaNative.stopEngine()
        }
    }
}

class NativeLuaScriptRunnerFactory : ScriptRunnerFactory {
    override fun create(ctx: ScriptRunContext): ScriptRunner = NativeLuaScriptRunner(ctx)
}
