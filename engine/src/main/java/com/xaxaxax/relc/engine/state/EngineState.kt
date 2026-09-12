package com.xaxaxax.relc.engine.state

/** Run-state of the native script engine, as observed from Kotlin. */
sealed interface EngineRunState {
    /** No script has run yet, or the engine was reset before the native RUNNING event arrived. */
    data object Idle : EngineRunState

    /** [com.xaxaxax.relc.engine.ScriptEngine.start] was called; native init is in flight. */
    data object Starting : EngineRunState

    /** Native init succeeded and `main.lua` is executing. */
    data object Running : EngineRunState

    /** `main.lua` ran to completion. Scripts are linear — reaching the end means done. */
    data object Finished : EngineRunState

    /** The script terminated because of a Lua/native error. */
    data class Error(val message: String) : EngineRunState

    /** The engine was stopped explicitly, not by error or normal completion. */
    data object Stopped : EngineRunState
}

/**
 * 一次 `vision.find` / `vision.wait` 的結果。座標都是**邏輯空間**。
 *
 * 這是回報給 UI 用的快照，不是腳本拿到的那個 table——腳本那邊拿到的是 nil 或一筆命中。
 */
data class VisionResult(
    /** 模板的絕對路徑，供 UI 顯示是哪張圖。 */
    val name: String,
    val found: Boolean,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val centerX: Double,
    val centerY: Double,
    val confidence: Double,
)

data class EngineState(
    val runState: EngineRunState = EngineRunState.Idle,
    /** 目前（或最後一次）執行的腳本識別，讓 UI 不必自己對照。 */
    val runningScriptId: String? = null,
    val lastVisionResult: List<VisionResult> = emptyList(),
)
