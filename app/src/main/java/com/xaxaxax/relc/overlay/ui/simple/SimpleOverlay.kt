package com.xaxaxax.relc.overlay.ui.simple

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.updateViewLayout

context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
@Composable
fun SimpleOverlay(
    viewModel: SimpleOverlayViewModel = hiltViewModel(),
    onClose: () -> Unit
) {
    var offset by remember { mutableStateOf(Offset(0f, 0f)) }
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
                onDrag = { dx, dy ->
                    overlayWindowScope.updateViewLayout(ViewKeyType.Root) { origParams ->
                        offset += Offset(dx, dy)
                        origParams.x = offset.x.toInt()
                        origParams.y = offset.y.toInt()
                        origParams
                    }
                }
            )
    }

}