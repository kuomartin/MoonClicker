package com.xaxaxax.relc.script

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ScriptRepository {
    private val _scripts = MutableStateFlow<List<ScriptConfig>>(emptyList())
    val scripts: StateFlow<List<ScriptConfig>> = _scripts.asStateFlow()

    init {
        // Load dummy data
        _scripts.value = listOf(
            ScriptConfig(
                id = UUID.randomUUID().toString(),
                name = "Demo Tap Script",
                description = "Taps the center of the screen",
                code = """
                    log("Starting tap script")
                    input.tap(540, 960, 0)
                    sleep(1000)
                    log("Tap script finished")
                """.trimIndent()
            ),
            ScriptConfig(
                id = UUID.randomUUID().toString(),
                name = "Infinite Swipe",
                description = "Swipes continuously",
                code = """
                    log("Swiping...")
                    input.swipe(540, 1500, 540, 500, 500, 0)
                """.trimIndent(),
                loopMode = LoopMode.INFINITE,
                intervalMs = 1000
            )
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
