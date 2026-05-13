package com.xaxaxax.relc.script

data class ScriptConfig(
    val id: String,
    val name: String,
    val description: String,
    val type: ScriptCodeType = ScriptCodeType.LUA,
    val init: String? = null,
    val code: String,
    val clean: String? = null,
    val alwaysRunClean: Boolean = false,
    val loopMode: LoopMode = LoopMode.None,
)
