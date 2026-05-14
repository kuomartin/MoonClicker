package com.xaxaxax.relc.script

import android.content.Context
import com.xaxaxax.relc.script.simple.SimpleScriptBodyJson
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

class ScriptRepository(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val scriptFile = File(context.filesDir, "scripts.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _scripts = MutableStateFlow<List<ScriptConfig>>(emptyList())
    val scripts: StateFlow<List<ScriptConfig>> = _scripts.asStateFlow()

    init {
        loadScripts()
    }

    private fun loadScripts() {
        if (scriptFile.exists()) {
            try {
                val content = scriptFile.readText()
                _scripts.value = json.decodeFromString<List<ScriptConfig>>(content)
                Timber.d("Scripts loaded from disk: ${_scripts.value.size}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load scripts from disk")
                loadDefaults()
            }
        } else {
            loadDefaults()
        }
    }

    private fun loadDefaults() {
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
        saveToDisk()
    }

    private fun saveToDisk() {
        scope.launch {
            try {
                val content = json.encodeToString(_scripts.value)
                scriptFile.writeText(content)
                Timber.d("Scripts saved to disk")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save scripts to disk")
            }
        }
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
        saveToDisk()
    }

    fun deleteScript(id: String) {
        _scripts.value = _scripts.value.filter { it.id != id }
        saveToDisk()
    }
}
