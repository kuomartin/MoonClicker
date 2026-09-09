package com.xaxaxax.relc.ui.lua

import java.io.File

interface LuaUiScope {
    fun sendUIEvent(id: String, event: String)
    val rootPath: java.io.File
    val sharedData: Map<String, Any>
}
