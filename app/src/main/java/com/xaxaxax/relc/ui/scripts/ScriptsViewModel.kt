package com.xaxaxax.relc.ui.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.ShizukuStatusUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager
) : ViewModel() {
    val shizukuStatus: StateFlow<ShizukuStatusUiState> = shizukuManager.statusFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ShizukuStatusUiState()
    )

    fun onShizukuAction() = shizukuManager.requestPermissionOrConnect()
}
