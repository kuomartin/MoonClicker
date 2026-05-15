package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.display.NativeEngineSink
import com.xaxaxax.relc.script.ScriptConfig
import kotlinx.coroutines.delay

class NativeLuaScriptRunner(
    private val ctx: ScriptRunContext,
) : ScriptRunner {

    override suspend fun run(config: ScriptConfig) {
        val v2Service = ctx.service
        val size = v2Service.getDisplaySize(0)
        val width = size[0]
        val height = size[1]

        val sink = NativeEngineSink(v2Service, config.code, width, height)

        // Starts the engine in a new thread. Lua script decides when to create the VirtualDisplay.
        sink.start()

        try {
            while (ctx.isActive()) {
                delay(500)
            }
        } finally {
            sink.stop()
            sink.release()
        }
    }
}

class NativeLuaScriptRunnerFactory : ScriptRunnerFactory {
    override fun create(ctx: ScriptRunContext): ScriptRunner = NativeLuaScriptRunner(ctx)
}
