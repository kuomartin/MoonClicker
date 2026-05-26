package com.xaxaxax.relc.simplescript.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.simplescript.data.repository.ScriptRepository
import com.xaxaxax.relc.simplescript.domain.model.Script
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SimpleScriptViewModel @Inject constructor(
    private val repository: ScriptRepository
) : ViewModel() {

    val scripts: StateFlow<List<Script>> = repository.getAllScripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentScript = MutableStateFlow<Script?>(null)
    val currentScript: StateFlow<Script?> = _currentScript.asStateFlow()

    private val _originalScript = MutableStateFlow<Script?>(null)

    private var isLoaded = false

    fun loadScript(scriptId: Long?) {
        if (isLoaded) return
        isLoaded = true

        viewModelScope.launch {
            if (scriptId == null || scriptId == 0L) {
                // New Script
                val newScript = Script(name = "")
                _currentScript.value = newScript
                _originalScript.value = newScript.copy()
            } else {
                val loaded = repository.getScriptWithChildren(scriptId)
                _currentScript.value = loaded
                _originalScript.value = loaded?.copy()
            }
        }
    }

    fun isDirty(): Boolean {
        return _currentScript.value != _originalScript.value
    }

    fun saveCurrentScript() {
        val script = _currentScript.value ?: return
        viewModelScope.launch {
            repository.saveScript(script)
            _originalScript.value = script.copy()
        }
    }
    fun updateCurrentScript(updatedScript: Script) {
        _currentScript.value = updatedScript
    }

    // TODO: Add functions to handle Events, Conditions, and Actions updates
    // within the _currentScript state object if we want a single global state,
    // or just let the Editor screens return updated objects that we copy into currentScript.
}
