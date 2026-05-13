package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.input.InputController
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptEngine
import com.xaxaxax.relc.script.simple.SimplePhysicalKey
import com.xaxaxax.relc.script.simple.SimpleScriptBodyJson
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import com.xaxaxax.relc.script.simple.SimpleStepJson
import com.xaxaxax.relc.script.simple.SimpleStepKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class SimpleScriptRunner(
    private val ctx: ScriptRunContext,
) : ScriptRunner {

    override suspend fun run(config: ScriptConfig) {
        val body = try {
            SimpleScriptCodec.decode(config.code)
        } catch (e: Exception) {
            ctx.onLog("Invalid SIMPLE JSON: ${e.message}")
            throw e
        }

        val input = InputController(ctx.service)
        val hookEngine =
            if (!config.init.isNullOrBlank() || !config.clean.isNullOrBlank()) {
                ScriptEngine(ctx.service, ctx.onLog)
            } else {
                null
            }

        var finishedNormally = false
        try {
            hookEngine?.executeIfPresent(config.init)
            macroIterate(config.loopMode) {
                if (!ctx.isActive()) throw CancellationException("Script cancelled")
                for (step in body.steps) {
                    executeSimpleStep(step, body, input)
                }
            }
            finishedNormally = true
        } finally {
            runHookClean(hookEngine, config, finishedNormally)
        }
    }

    private suspend fun runHookClean(
        hookEngine: ScriptEngine?,
        config: ScriptConfig,
        finishedNormally: Boolean
    ) {
        val clean = config.clean?.takeIf { it.isNotBlank() } ?: return
        if (hookEngine == null) return
        if (!config.alwaysRunClean && !finishedNormally) return
        try {
            hookEngine.execute(clean)
        } catch (_: Exception) {
            // swallow
        }
    }

    private suspend fun executeSimpleStep(
        step: SimpleStepJson,
        body: SimpleScriptBodyJson,
        input: InputController,
    ) {
        require(step.repeatCount >= 1) { "repeatCount must be >= 1" }
        val displayId = step.displayId ?: body.defaultDisplayId

        repeat(step.repeatCount) { rep ->
            when (step.kind) {
                SimpleStepKind.TAP -> {
                    val x = step.x ?: error("TAP requires x")
                    val y = step.y ?: error("TAP requires y")
                    input.tap(x, y, displayId)
                }

                SimpleStepKind.SWIPE -> {
                    input.swipe(
                        step.x1 ?: error("SWIPE requires x1"),
                        step.y1 ?: error("SWIPE requires y1"),
                        step.x2 ?: error("SWIPE requires x2"),
                        step.y2 ?: error("SWIPE requires y2"),
                        step.durationMs ?: error("SWIPE requires durationMs"),
                        displayId,
                    )
                }

                SimpleStepKind.DELAY -> delay(step.delayMs ?: error("DELAY requires delayMs"))

                SimpleStepKind.KEY -> {
                    val keyName = step.key ?: error("KEY requires key")
                    input.injectPhysicalKey(SimplePhysicalKey.parse(keyName), displayId)
                }

                SimpleStepKind.TEXT -> ctx.onLog("TEXT step skipped (not implemented)")
            }
            if (rep < step.repeatCount - 1 && step.delayBetweenRepeatsMs > 0) {
                delay(step.delayBetweenRepeatsMs)
            }
        }
        if (step.delayAfterStepMs > 0) delay(step.delayAfterStepMs)
    }
}

class SimpleScriptRunnerFactory : ScriptRunnerFactory {
    override fun create(ctx: ScriptRunContext): ScriptRunner = SimpleScriptRunner(ctx)
}
