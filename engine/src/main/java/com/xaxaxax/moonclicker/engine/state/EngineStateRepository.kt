package com.xaxaxax.moonclicker.engine.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import timber.log.Timber

/**
 * 一個 [com.xaxaxax.moonclicker.engine.ScriptEngine] 實例的狀態，由 C++ 端推來的 [EngineEventType]
 * 事件（見 ScriptRuntime::pushEvent）經 [com.xaxaxax.moonclicker.script.ScriptHost] 餵進來。
 * UI 觀察這裡，不輪詢引擎是否在跑。
 */
internal class EngineStateRepository(scriptId: String) {
    private val _state = MutableStateFlow(
        EngineState(runState = EngineRunState.Starting, runningScriptId = scriptId)
    )
    val state: StateFlow<EngineState> = _state.asStateFlow()

    fun onEvent(type: Int, payload: String) {
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
