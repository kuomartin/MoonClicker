package com.xaxaxax.relc.overlay.ui.simple

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope

context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
@Composable
fun SimpleOverlay(
    viewModel: SimpleOverlayViewModel = hiltViewModel(),
    onClose: () -> Unit,
    onDrag: (Offset) -> Unit
) {
    val uiState = viewModel.uiState.collectAsState()
    when {
        uiState.value.isEditing -> DetailedScriptEditor(
            config = uiState.value.config,
            isRunning = uiState.value.isRunning,
            onNavigateBack = { viewModel.exitEditScreen() },
            onUpdateConfig = { viewModel.updateConfig(it) },
            onSave = { viewModel.save() },
            onPlay = { viewModel.startScript() },
            onStop = { viewModel.stopScript() }
        )

        uiState.value.isRecording -> RecordingOverlay(
            onRecordingFinished = { viewModel.stopRecording(it) },
            onCancel = { viewModel.stopRecording(null) }
        )

        else ->
            EditorControlBar(
                uiState = uiState.value,
                onStartStopClick = {
                    if (uiState.value.isRunning) viewModel.stopScript()
                    else viewModel.startScript()
                },
                onAddTapClick = { viewModel.addTap() },
                onRecordSwipeClick = { viewModel.startRecording() },
                onRemoveClick = { viewModel.removeScriptLine() },
                onSaveClick = { viewModel.save() },
                onCloseClick = onClose,
                onMoreClick = { viewModel.startEdit() },
                onDrag = onDrag
            )
    }

}