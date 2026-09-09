package com.xaxaxax.relc.ui.scripts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.overlay.OverlayController
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ScriptRepository,
    private val scriptManager: ScriptManager,
    private val overlayController: OverlayController,
) : ViewModel() {

    val scripts = repository.scripts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val scriptStates = scriptManager.scriptStates.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyMap()
    )

    fun startScript(config: ScriptConfig) {
        val success = overlayController.showOverlay(config.id, config.type.name)
        if (!success) {
            android.widget.Toast.makeText(context, "請先在設定中開啟無障礙服務", android.widget.Toast.LENGTH_SHORT).show()
        }
        scriptManager.startScript(config)
    }

    fun stopScript(id: String) {
        scriptManager.stopScript(id)
    }

    fun deleteScript(id: String) {
        repository.deleteScript(id)
    }
}
