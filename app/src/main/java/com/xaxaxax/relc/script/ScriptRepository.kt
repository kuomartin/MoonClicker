package com.xaxaxax.relc.script

import com.xaxaxax.relc.script.simple.SimpleScriptBodyJson
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ScriptRepository {
    private val _scripts = MutableStateFlow<List<ScriptConfig>>(emptyList())
    val scripts: StateFlow<List<ScriptConfig>> = _scripts.asStateFlow()

    init {
        _scripts.value = listOf(
            ScriptConfig(
                id = UUID.randomUUID().toString(),
                name = "Demo Tap Script",
                description = "Lua taps center",
                type = ScriptCodeType.LUA,
                init = null,
                code = """
                    log("Starting tap script")
                    displayId = 0
                    input.tap(50, 540, 960)
                    sleep(1000)
                    log("Tap script finished")
                """.trimIndent(),
                clean = null,
                alwaysRunClean = false,
                loopMode = LoopMode.None,
            ),
            ScriptConfig(
                id = UUID.randomUUID().toString(),
                name = "Infinite Swipe",
                description = "Lua swipes continuously",
                type = ScriptCodeType.LUA,
                init = null,
                code = """
                    log("Swiping...")
                    displayId = 0
                    input.swipe(500, 540, 1500, 540, 500)
                """.trimIndent(),
                clean = null,
                alwaysRunClean = false,
                loopMode = LoopMode.Inf(),
            ),
            ScriptConfig(
                id = UUID.randomUUID().toString(),
                name = "Simple Tap Demo",
                description = "JSON SIMPLE script",
                type = ScriptCodeType.SIMPLE,
                init = null,
                code = SimpleScriptCodec.encode(
                    SimpleScriptBodyJson(
                        steps = listOf(
                            "tap:1:0:500:540,960",
                        ),
                    ),
                ),
                clean = null,
                alwaysRunClean = false,
                loopMode = LoopMode.None,
            ),
        )
    }

    fun getScript(id: String): ScriptConfig? {
        return _scripts.value.find { it.id == id }
    }

    fun saveScript(config: ScriptConfig) {
        val currentList = _scripts.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == config.id }
        if (index != -1) {
            currentList[index] = config
        } else {
            currentList.add(config)
        }
        _scripts.value = currentList
    }

    fun deleteScript(id: String) {
        _scripts.value = _scripts.value.filter { it.id != id }
    }
}
