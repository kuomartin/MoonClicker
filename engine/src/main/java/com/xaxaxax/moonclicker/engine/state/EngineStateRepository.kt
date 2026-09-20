package com.xaxaxax.moonclicker.engine.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import timber.log.Timber

/**
 * Single observable source of truth for native engine state, fed by [EngineEventType]
 * events pushed from the C++ side (see ScriptRuntime::pushEvent) via
 * [com.xaxaxax.moonclicker.script.ScriptHost.onEngineEvent]. This is what UI/consumer modules
 * should observe instead of polling whether the engine is running.
 */
object EngineStateRepository {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Called right before starting a script, so stale terminal state from a previous run is
     * never mistaken for the new run's outcome.
     */
    fun reset(scriptId: String?) {
        _state.value = EngineState(
            runState = EngineRunState.Starting,
            runningScriptId = scriptId,
        )
    }

    internal fun onEvent(type: Int, payload: String) {
        when (type) {
            EngineEventType.RUNNING -> _state.update { it.copy(runState = EngineRunState.Running) }
            EngineEventType.FINISHED -> _state.update { it.copy(runState = EngineRunState.Finished) }
            EngineEventType.ERROR -> _state.update { it.copy(runState = EngineRunState.Error(payload)) }
            EngineEventType.STOPPED -> _state.update { it.copy(runState = EngineRunState.Stopped) }
            EngineEventType.VISION_RESULT -> _state.update {
                it.copy(lastVisionResult = parseVisionResults(payload))
            }

            else -> Timber.w("EngineStateRepository: unknown event type $type")
        }
    }

    private fun parseVisionResults(payload: String): List<VisionResult> = try {
        val array = JSONArray(payload)
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    VisionResult(
                        name = o.getString("name"),
                        found = o.getBoolean("found"),
                        x = o.getDouble("x"),
                        y = o.getDouble("y"),
                        width = o.getDouble("width"),
                        height = o.getDouble("height"),
                        centerX = o.getDouble("cx"),
                        centerY = o.getDouble("cy"),
                        confidence = o.getDouble("confidence"),
                    )
                )
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "EngineStateRepository: failed to parse vision result payload: $payload")
        emptyList()
    }
}

/** Must match the `type` constants ScriptRuntime::pushEvent sends over JNI. */
object EngineEventType {
    const val RUNNING = 0
    const val FINISHED = 1
    const val ERROR = 2
    const val STOPPED = 3
    const val VISION_RESULT = 4
}
