package com.xaxaxax.relc.ui.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    private val repository: ScriptRepository,
    private val scriptManager: ScriptManager
) : ViewModel() {

    val scripts = repository.scripts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val scriptState = scriptManager.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        com.xaxaxax.relc.script.ScriptState.IDLE
    )

    fun startScript(config: ScriptConfig) {
        scriptManager.startScript(config)
    }

    fun stopScript() {
        scriptManager.stopScript()
    }

    fun deleteScript(id: String) {
        repository.deleteScript(id)
    }
}
