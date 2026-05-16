package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.input.InputController
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.SimpleScriptProgress
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SimplePhysicalKey
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.parseSimpleScriptLine
import com.xaxaxax.relc.script.simple.parseSwipePayload
import com.xaxaxax.relc.script.simple.parseTapPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

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

        val logicalStepTotal = body.steps.count { it.trim().isNotEmpty() }
        val emitProgress = ctx.onSimpleProgress ?: { _: SimpleScriptProgress -> }

        macroIterateIndexed(config.loopMode) { macroIter, macroTotal ->
            if (!ctx.isActive()) throw CancellationException("Script cancelled")
            var displayId = 0
            var logical = 0
            for (raw in body.steps) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                logical++
                emitProgress(
                    SimpleScriptProgress(
                        logicalStepIndex = logical,
                        logicalStepTotal = logicalStepTotal,
                        macroIteration = macroIter,
                        macroTotal = macroTotal,
                    )
                )
                val parsed = try {
                    parseSimpleScriptLine(line)
                } catch (e: Exception) {
                    ctx.onLog("Bad SIMPLE line: $line — ${e.message}")
                    throw e
                }
                displayId = executeParsedLine(parsed, displayId, input)
            }
        }
    }

    /**
     * Runs one line; returns updated display id ([SimpleScriptVerb.SET_DISPLAY] changes it).
     */
    private suspend fun executeParsedLine(
        p: ParsedSimpleLine,
        displayId: Int,
        input: InputController,
    ): Int {
        when (p.verb) {
            SimpleScriptVerb.SET_DISPLAY -> {
                val id = p.payload.trim().toIntOrNull()
                    ?: error("setDisplay payload must be an int")
                if (p.delayAfterStepMs > 0) delay(p.delayAfterStepMs.milliseconds)
                return id
            }

            else -> {
                repeat(p.repeatCount) { rep ->
                    when (p.verb) {
                        SimpleScriptVerb.TAP -> {
                            val tap = parseTapPayload(p.payload)
                            input.tap(tap.durationMs, tap.x, tap.y, displayId)
                        }

                        SimpleScriptVerb.SWIPE, SimpleScriptVerb.SWIPE_RAW -> {
                            val s = parseSwipePayload(p.payload)
                            input.swipePolyline(
                                s.durationMs,
                                points = s.points,
                                displayId = displayId
                            )
                        }

                        SimpleScriptVerb.DELAY -> {
                            val ms = p.payload.trim().toLongOrNull()
                                ?: error("delay payload must be delayMs")
                            delay(ms.milliseconds)
                        }

                        SimpleScriptVerb.KEY -> {
                            val keyName = p.payload.trim()
                            require(keyName.isNotEmpty()) { "key payload empty" }
                            input.injectPhysicalKey(
                                SimplePhysicalKey.parse(keyName),
                                displayId,
                            )
                        }

                        SimpleScriptVerb.TEXT -> {
                            ctx.onLog("TEXT step skipped (not implemented): ${p.payload}")
                        }

                        SimpleScriptVerb.SET_DISPLAY -> error("internal")
                    }
                    if (rep < p.repeatCount - 1 && p.delayBetweenRepeatsMs > 0) {
                        delay(p.delayBetweenRepeatsMs.milliseconds)
                    }
                }
                if (p.delayAfterStepMs > 0) delay(p.delayAfterStepMs.milliseconds)
                return displayId
            }
        }
    }
}

class SimpleScriptRunnerFactory : ScriptRunnerFactory {
    override fun create(ctx: ScriptRunContext): ScriptRunner = SimpleScriptRunner(ctx)
}
