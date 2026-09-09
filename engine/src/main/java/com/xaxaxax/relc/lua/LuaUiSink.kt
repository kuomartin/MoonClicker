package com.xaxaxax.relc.lua

/**
 * Receives UI element mutations pushed from the native Lua engine.
 * Implemented on the UI side (overlay module) and registered via [LuaNative.uiSink]
 * so the engine module never depends on Compose UI rendering code.
 */
interface LuaUiSink {
    fun clear()
    fun add(parentId: String?, id: String, jsonExp: String)
    fun update(id: String, jsonExp: String)
    fun remove(id: String)
}
