package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptEngine
import kotlinx.coroutines.CancellationException

class LuaScriptRunner(
    private val ctx: ScriptRunContext,
) : ScriptRunner {

    override suspend fun run(config: ScriptConfig) {
        val engine = ScriptEngine(ctx.service, ctx.onLog)
        var finishedNormally = false
        try {
            engine.executeIfPresent(config.init)
            macroIterate(config.loopMode) {
                if (!ctx.isActive()) throw CancellationException("Script cancelled")
                engine.execute(config.code)
            }
            finishedNormally = true
        } finally {
            runClean(engine, config, finishedNormally)
        }
    }

    private suspend fun runClean(
        engine: ScriptEngine,
        config: ScriptConfig,
        finishedNormally: Boolean
    ) {
        val clean = config.clean?.takeIf { it.isNotBlank() } ?: return
        if (!config.alwaysRunClean && !finishedNormally) return
        try {
            engine.execute(clean)
        } catch (_: Exception) {
            // intentionally swallow clean failures
        }
    }
}

class LuaScriptRunnerFactory : ScriptRunnerFactory {
    override fun create(ctx: ScriptRunContext): ScriptRunner = LuaScriptRunner(ctx)
}
