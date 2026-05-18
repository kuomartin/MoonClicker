package com.xaxaxax.relc.script

import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ScriptConfig(@SerialName("scriptName") val type: ScriptCodeType) {
    abstract val id: String
    abstract val name: String
    abstract val description: String

    @Serializable
    data class Lua(
        override val id: String,
        override val name: String,
        override val description: String,
        val code: String
    ) : ScriptConfig(ScriptCodeType.LUA)

    @Serializable
    data class Simple(
        override val id: String,
        override val name: String,
        override val description: String,
        val steps: List<ParsedSimpleLine>,
        val loopMode: LoopMode = LoopMode.None,
    ) : ScriptConfig(ScriptCodeType.SIMPLE) {
        companion object {
            val Empty = Simple(
                "", "", "", emptyList()
            )
        }
    }

    enum class ScriptCodeType { LUA, SIMPLE }
}
