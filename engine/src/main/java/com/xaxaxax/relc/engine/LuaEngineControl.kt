package com.xaxaxax.relc.engine

import android.view.Surface
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.lua.LuaNative
import com.xaxaxax.relc.lua.LuaUiSink

/**
 * Public facade over the Lua engine's JNI bridge ([LuaNative]). Other modules should
 * go through this instead of importing `com.xaxaxax.relc.lua.LuaNative` directly —
 * that class is `internal` to :engine so the compiler enforces this boundary.
 *
 * For run-state observability (running/finished/error/stopped, latest match result),
 * observe [com.xaxaxax.relc.engine.state.EngineStateRepository.state] instead of
 * polling anything on this facade.
 */
object LuaEngineControl {
    val sharedData: Map<String, Any> get() = LuaNative.sharedData

    var uiSink: LuaUiSink?
        get() = LuaNative.uiSink
        set(value) {
            LuaNative.uiSink = value
        }

    fun startEngineWithService(
        service: IRelcV2Service,
        displayId: Int,
        width: Int,
        height: Int,
        scriptPath: String
    ): Surface? = LuaNative.startEngineWithService(service, displayId, width, height, scriptPath)

    fun stop() = LuaNative.stop()

    fun sendUIEvent(elementId: String, eventType: String) =
        LuaNative.sendUIEvent(elementId, eventType)
}
