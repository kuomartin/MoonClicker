package com.xaxaxax.relc.engine.state

/** Run-state of the native Lua engine, as observed from Kotlin. */
sealed interface EngineRunState {
    /** No script has run yet, or the engine was reset before the native RUNNING event arrived. */
    data object Idle : EngineRunState

    /** [com.xaxaxax.relc.lua.LuaNative.startEngineWithService] was called; native init is in flight. */
    data object Starting : EngineRunState

    /** Native init succeeded and the script's main loop is executing. */
    data object Running : EngineRunState

    /** The script ran to completion (no on_tick, or the coroutine finished normally). */
    data object Finished : EngineRunState

    /** The script terminated because of a Lua/native error. */
    data class Error(val message: String) : EngineRunState

    /** The engine was stopped explicitly (LuaNative.stop()), not by error or normal completion. */
    data object Stopped : EngineRunState
}

data class MatchResult(
    val name: String,
    val found: Boolean,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val confidence: Double,
)

data class EngineState(
    val runState: EngineRunState = EngineRunState.Idle,
    val lastMatchResult: List<MatchResult> = emptyList(),
)
