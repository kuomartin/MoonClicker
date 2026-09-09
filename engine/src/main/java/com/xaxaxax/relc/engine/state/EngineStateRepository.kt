package com.xaxaxax.relc.engine.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import timber.log.Timber

/**
 * Single observable source of truth for native engine state, fed by [EngineEventType]
 * events pushed from the C++ side (see RelcEngine::pushEngineEvent) via
 * [com.xaxaxax.relc.lua.LuaNative.onEngineEvent]. This is what UI/consumer modules
 * should observe instead of polling `LuaNative.isEngineRunning()`.
 */
object EngineStateRepository {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Called by LuaNative right before starting the native engine, so stale terminal
     * state from a previous run is never mistaken for the new run's outcome. */
    fun reset() {
        _state.value = EngineState(runState = EngineRunState.Starting)
    }

    internal fun onEvent(type: Int, payload: String) {
        when (type) {
            EngineEventType.RUNNING -> _state.update { it.copy(runState = EngineRunState.Running) }
            EngineEventType.FINISHED -> _state.update { it.copy(runState = EngineRunState.Finished) }
            EngineEventType.ERROR -> _state.update { it.copy(runState = EngineRunState.Error(payload)) }
            EngineEventType.STOPPED -> _state.update { it.copy(runState = EngineRunState.Stopped) }
            EngineEventType.MATCH_RESULT -> _state.update {
                it.copy(lastMatchResult = parseMatchResults(payload))
            }

            else -> Timber.w("EngineStateRepository: unknown event type $type")
        }
    }

    private fun parseMatchResults(payload: String): List<MatchResult> = try {
        val array = JSONArray(payload)
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    MatchResult(
                        name = o.getString("name"),
                        found = o.getBoolean("found"),
                        x = o.getDouble("x"),
                        y = o.getDouble("y"),
                        width = o.getDouble("width"),
                        height = o.getDouble("height"),
                        confidence = o.getDouble("confidence"),
                    )
                )
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "EngineStateRepository: failed to parse match result payload: $payload")
        emptyList()
    }
}

/** Must match the `type` constants RelcEngine::pushEngineEvent sends over JNI. */
object EngineEventType {
    const val RUNNING = 0
    const val FINISHED = 1
    const val ERROR = 2
    const val STOPPED = 3
    const val MATCH_RESULT = 4
}
