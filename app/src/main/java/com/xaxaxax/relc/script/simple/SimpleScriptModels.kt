package com.xaxaxax.relc.script.simple

import kotlinx.serialization.Serializable

@Serializable
enum class SimpleStepKind {
    TAP,
    SWIPE,
    DELAY,
    KEY,
    TEXT,
}

/**
 * Serialized SIMPLE script body stored in [com.xaxaxax.relc.script.ScriptConfig.code].
 */
@Serializable
data class SimpleScriptBodyJson(
    val defaultDisplayId: Int = 0,
    val steps: List<SimpleStepJson> = emptyList(),
)

@Serializable
data class SimpleStepJson(
    val kind: SimpleStepKind,
    val repeatCount: Int = 1,
    val delayBetweenRepeatsMs: Long = 0,
    val delayAfterStepMs: Long = 0,
    val x: Int? = null,
    val y: Int? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val durationMs: Long? = null,
    val delayMs: Long? = null,
    /** Override [SimpleScriptBodyJson.defaultDisplayId] for this step when set. */
    val displayId: Int? = null,
    /** For [SimpleStepKind.KEY]: BACK, POWER, VOLUME_UP, VOLUME_DOWN, HOME */
    val key: String? = null,
    val text: String? = null,
)
