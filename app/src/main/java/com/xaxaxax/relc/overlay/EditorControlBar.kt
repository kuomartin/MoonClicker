package com.xaxaxax.relc.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xaxaxax.relc.ui.theme.ReLCTheme

data class EditorControlUiState(
    val isRunning: Boolean = false,
    val isRecording: Boolean = false,
)

@Composable
fun EditorControlBar(
    uiState: EditorControlUiState,
    onStartStopClick: () -> Unit,
    onAddTapClick: () -> Unit,
    onRecordSwipeClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onSaveClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDrag: (Float, Float) -> Unit = { _, _ -> }
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(visible = isExpanded) {
            Card(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .wrapContentWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier.clickable { onSaveClick() },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                        Text("儲存", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }

                    Column(
                        modifier = Modifier.clickable { onCloseClick() },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Red)
                        Text("退出", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .height(56.dp)
                .padding(4.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 1. Start/Stop
                ControlIconButton(
                    icon = if (uiState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (uiState.isRunning) "Stop" else "Start",
                    tint = if (uiState.isRunning) Color.Red else Color(0xFF4CAF50),
                    onClick = onStartStopClick
                )

                EditorVerticalDivider()

                // 2. Add Tap
                ControlIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "Add Tap",
                    onClick = onAddTapClick,
                    enabled = !uiState.isRunning && !uiState.isRecording
                )

                // 3. Record Swipe
                ControlIconButton(
                    icon = Icons.Default.RadioButtonChecked,
                    contentDescription = "Record Swipe",
                    tint = if (uiState.isRecording) Color.Red else Color.DarkGray,
                    onClick = onRecordSwipeClick,
                    enabled = !uiState.isRunning
                )

                // 4. Remove Last
                ControlIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = "Remove Last",
                    onClick = onRemoveClick,
                    enabled = !uiState.isRunning && !uiState.isRecording
                )

                EditorVerticalDivider()

                // 5. More
                ControlIconButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "More",
                    onClick = { isExpanded = !isExpanded }
                )
            }
        }
    }
}

@Composable
private fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.DarkGray
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) tint else Color.LightGray
        )
    }
}

@Composable
private fun EditorVerticalDivider() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .width(1.dp)
            .background(Color.LightGray.copy(alpha = 0.5f))
    )
}

@Preview(showBackground = true)
@Composable
private fun EditorControlBarPreview() {
    ReLCTheme {
        Box(Modifier.padding(16.dp)) {
            EditorControlBar(
                uiState = EditorControlUiState(isRunning = false, isRecording = false),
                onStartStopClick = {},
                onAddTapClick = {},
                onRecordSwipeClick = {},
                onRemoveClick = {},
                onSaveClick = {},
                onCloseClick = {}
            )
        }
    }
}
