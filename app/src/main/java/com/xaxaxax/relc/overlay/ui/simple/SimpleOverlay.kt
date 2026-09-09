package com.xaxaxax.relc.overlay.ui.simple

import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.ViewKeyType
import com.xaxaxax.relc.overlay.ui.addComposable
import com.xaxaxax.relc.overlay.ui.removeView
import com.xaxaxax.relc.overlay.ui.updateViewLayout

context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
@Composable
fun SimpleOverlay(
    viewModel: SimpleOverlayViewModel = hiltViewModel(),
    onClose: () -> Unit,
    onDrag: (Offset) -> Unit
) {
    val uiState = viewModel.uiState.collectAsState()

    var savedX by remember { mutableIntStateOf(0) }
    var savedY by remember { mutableIntStateOf(0) }

    if (uiState.value.isRecording) {
        DisposableEffect(Unit) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            overlayWindowScope.addComposable(ViewKeyType.Recording, params) {
                RecordingOverlay(
                    onRecordingFinished = { viewModel.stopRecording(it) },
                    onCancel = { viewModel.stopRecording(null) }
                )
            }
            onDispose {
                overlayWindowScope.removeView(ViewKeyType.Recording)
            }
        }
    }

    LaunchedEffect(uiState.value.isEditing) {
        overlayWindowScope.updateViewLayout(ViewKeyType.Root) { params ->
            if (uiState.value.isEditing) {
                if (params.width == WindowManager.LayoutParams.WRAP_CONTENT) {
                    savedX = params.x
                    savedY = params.y
                }
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
            } else {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                if (savedX != 0 || savedY != 0) {
                    params.x = savedX
                    params.y = savedY
                }
            }
            params
        }
    }

    LaunchedEffect(uiState.value.config) {
        val config = uiState.value.config
        if (config.steps.isNotEmpty()) {
            viewModel.updatePreviewViews()
        }
    }

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
                onCollapsedChange = { viewModel.setCollapsed(it) },
                onCloseClick = onClose,
                onMoreClick = { viewModel.startEdit() },
                onDrag = onDrag
            )
    }

}
