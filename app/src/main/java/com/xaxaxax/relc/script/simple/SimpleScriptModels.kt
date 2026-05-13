package com.xaxaxax.relc.script.simple

import kotlinx.serialization.Serializable

/**
 * Serialized SIMPLE script body stored in [com.xaxaxax.relc.script.ScriptConfig.code].
 *
 * Each entry is one line:
 * `verb:repeat:delayBetweenRepeatsMs:delayAfterStepMs:payload`
 *
 * Only the first four `:` delimiters split the prefix; the remainder is payload (may contain colons).
 */
@Serializable
data class SimpleScriptBodyJson(
    val steps: List<String> = emptyList(),
)
