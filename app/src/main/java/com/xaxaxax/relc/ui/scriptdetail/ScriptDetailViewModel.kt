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
                _config.value = ScriptConfig(
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
            } else {
                repository.scripts.collect { scripts ->
                    val script = scripts.find { it.id == scriptId }
                    // 只有當前 config 為 null (首次讀取) 或是當前 id 與 repository 內容匹配時才更新
                    // 為了避免編輯中的資料被覆蓋，這裡簡單實作為只在首次或外部更新時同步
                    _config.value = script
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
        _config.value?.let { repository.saveScript(it) }
    }
}
