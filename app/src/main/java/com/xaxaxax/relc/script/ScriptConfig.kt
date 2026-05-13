package com.xaxaxax.relc.script

enum class LoopMode {
    SINGLE, INFINITE, COUNTED
}

data class ScriptConfig(
    val id: String,
    val name: String,
    val description: String,
    val code: String,
    val loopMode: LoopMode = LoopMode.SINGLE,
    val loopCount: Int = 1,
    val intervalMs: Long = 0
)
