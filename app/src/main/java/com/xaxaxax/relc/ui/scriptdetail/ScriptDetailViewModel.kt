package com.xaxaxax.relc.ui.scriptdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.script.LoopMode
import com.xaxaxax.relc.script.ScriptCodeType
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import com.xaxaxax.relc.script.ScriptState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ScriptDetailViewModel @Inject constructor(
    private val repository: ScriptRepository,
    val scriptManager: ScriptManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val scriptId: String = checkNotNull(savedStateHandle["id"])

    private val _config = MutableStateFlow<ScriptConfig?>(null)
    val config = _config.asStateFlow()

    private val _initialConfig = MutableStateFlow<ScriptConfig?>(null)
    val hasChanges = kotlinx.coroutines.flow.combine(_config, _initialConfig) { current, initial ->
        if (current == null || initial == null) false
        else current != initial
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val scriptState = scriptManager.scriptStates.map { states ->
        states[scriptId] ?: ScriptState.IDLE
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ScriptState.IDLE
    )

    val logs = scriptManager.logs
        .filter { it.scriptId == scriptId }
        .map { it.message }

    init {
        viewModelScope.launch {
            if (scriptId == "new") {
                val newCfg = ScriptConfig(
                    id = UUID.randomUUID().toString(),
                    name = "New Script",
                    description = "",
                    type = ScriptCodeType.LUA,
                    init = null,
                    code = "log(\"Hello ReLC\")\n",
                    clean = null,
                    alwaysRunClean = false,
                    loopMode = LoopMode.None,
                )
                _config.value = newCfg
                _initialConfig.value = newCfg
            } else {
                repository.scripts.collect { scripts ->
                    val script = scripts.find { it.id == scriptId }
                    if (_config.value == null && script != null) {
                        _config.value = script
                        _initialConfig.value = script
                    }
                }
            }
        }
    }

    fun updateConfig(update: (ScriptConfig) -> ScriptConfig) {
        _config.update { current ->
            current?.let { update(it) }
        }
    }

    fun saveScript() {
        _config.value?.let {
            repository.saveScript(it)
            _initialConfig.value = it
        }
    }
}
