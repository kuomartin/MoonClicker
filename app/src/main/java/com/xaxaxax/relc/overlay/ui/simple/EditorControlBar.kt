package com.xaxaxax.relc.overlay.ui.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.R
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun EditorControlBar(
    uiState: SimpleOverlayViewModel.UiState,
    onStartStopClick: () -> Unit,
    onAddTapClick: () -> Unit,
    onRecordSwipeClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onSaveClick: () -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
    onCloseClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDrag: (Offset) -> Unit = {}
) {
    Column(
        modifier = modifier.wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    }
                },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 1. Start/Stop
                ControlIconButton(
                    icon = if (uiState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (uiState.isRunning) "Stop" else "Start",
                    tint = if (uiState.isRunning) Color.Red else colorResource(R.color.start_green),
                    onClick = onStartStopClick
                )

                VerticalDivider(
                    Modifier.height(24.dp),
                    DividerDefaults.Thickness,
                    DividerDefaults.color
                )

                if (!uiState.isCollapsed) {
                    // 2. Add Tap
                    ControlIconButton(
                        icon = Icons.Default.Add,
                        contentDescription = "Add Tap",
                        onClick = onAddTapClick,
                        enabled = !uiState.isRunning && !uiState.isRecording
                    )

                    // 3. Remove Last
                    ControlIconButton(
                        painter = painterResource(R.drawable.ic_remove),
                        contentDescription = "Remove Last",
                        onClick = onRemoveClick,
                        enabled = !uiState.isRunning && !uiState.isRecording
                    )

                    // 4. Record Swipe
                    ControlIconButton(
                        icon = Icons.Default.RadioButtonChecked,
                        contentDescription = "Record Swipe",
                        tint = if (uiState.isRecording) Color.Red else Color.DarkGray,
                        onClick = onRecordSwipeClick,
                        enabled = !uiState.isRunning
                    )


                    // 5. Save
                    ControlIconButton(
                        painter = painterResource(R.drawable.ic_save),
                        contentDescription = "Save",
                        onClick = onSaveClick,
                        enabled = !uiState.isRunning && !uiState.isRecording
                    )

                    // 6. Hide
                    ControlIconButton(
                        painter = painterResource(R.drawable.ic_visibility_off),
                        contentDescription = "Hide",
                        onClick = { onCollapsedChange(true) }
                    )

                    // 7. More
                    ControlIconButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "More",
                        onClick = onMoreClick
                    )
                } else {
                    // 6. Show (Collapsed mode)
                    ControlIconButton(
                        painter = painterResource(R.drawable.ic_visibility),
                        contentDescription = "Show",
                        onClick = { onCollapsedChange(false) }
                    )
                }

                VerticalDivider(
                    Modifier.height(24.dp),
                    DividerDefaults.Thickness,
                    DividerDefaults.color
                )

                // 8. Exit
                ControlIconButton(
                    painter = painterResource(R.drawable.ic_exit_to_app),
                    contentDescription = "Exit",
                    onClick = onCloseClick
                )
            }
        }
    }
}

@Composable
private fun ControlIconButton(
    icon: ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.DarkGray
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = if (enabled) tint else Color.LightGray
            )
        } else if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = if (enabled) tint else Color.LightGray
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EditorControlBarPreview() {
    ReLCTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Expanded
            EditorControlBar(
                uiState = SimpleOverlayViewModel.UiState(
                    isRunning = false,
                    isRecording = false
                ),
                onStartStopClick = {},
                onAddTapClick = {},
                onRecordSwipeClick = {},
                onRemoveClick = {},
                onSaveClick = {},
                onCollapsedChange = {},
                onCloseClick = {},
                onMoreClick = {}
            )
            // Collapsed
            EditorControlBar(
                uiState = SimpleOverlayViewModel.UiState(
                    isRunning = true,
                    isRecording = false,
                ),
                onStartStopClick = {},
                onAddTapClick = {},
                onRecordSwipeClick = {},
                onRemoveClick = {},
                onSaveClick = {},
                onCollapsedChange = {},
                onCloseClick = {},
                onMoreClick = {}
            )
        }
    }
}
