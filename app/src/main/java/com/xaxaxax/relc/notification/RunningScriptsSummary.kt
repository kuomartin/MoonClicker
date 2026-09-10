package com.xaxaxax.relc.notification

import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptState

/** How many names are spelled out before the summary falls back to "及其他 N 個". */
private const val MAX_NAMED_SCRIPTS = 3

/**
 * What the status notification should say right now. `null` means nothing is running
 * and the notification should be cancelled.
 */
data class RunningScriptsSummary(
    val runningCount: Int,
    val text: String,
)

/**
 * Folds the script list and the per-script run states into the single line shown in the
 * status notification. Names follow [scripts] order so the notification and the Scripts
 * page agree; a running id with no matching config (deleted mid-run) falls back to the id.
 */
fun summarizeRunningScripts(
    scripts: List<ScriptConfig>,
    states: Map<String, ScriptState>,
): RunningScriptsSummary? {
    val runningIds = states.filterValues { it == ScriptState.RUNNING }.keys
    if (runningIds.isEmpty()) return null

    val namesById = scripts.associate { it.id to it.name }
    val orderedIds = scripts.map { it.id }.filter { it in runningIds } +
            runningIds.filterNot { namesById.containsKey(it) }
    val names = orderedIds.map { namesById[it] ?: it }

    val text = if (names.size <= MAX_NAMED_SCRIPTS) {
        names.joinToString("、")
    } else {
        val shown = names.take(MAX_NAMED_SCRIPTS).joinToString("、")
        "$shown 及其他 ${names.size - MAX_NAMED_SCRIPTS} 個"
    }

    return RunningScriptsSummary(runningCount = names.size, text = text)
}
