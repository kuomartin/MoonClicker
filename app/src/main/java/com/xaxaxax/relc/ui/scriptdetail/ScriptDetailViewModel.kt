package com.xaxaxax.relc.ui.scriptdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    init {
        if (scriptId == "new") {
            _config.value = ScriptConfig(
                id = UUID.randomUUID().toString(),
                name = "New Script",
                description = "",
                code = "log(\"Hello ReLC\")\n"
            )
        } else {
            _config.value = repository.getScript(scriptId)
        }
    }

    fun updateConfig(update: (ScriptConfig) -> ScriptConfig) {
        _config.update { current ->
            current?.let { update(it) }
        }
    }

    fun saveScript() {
        _config.value?.let { repository.saveScript(it) }
    }
}
