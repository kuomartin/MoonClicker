package com.xaxaxax.relc.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xaxaxax.relc.ui.theme.ReLCTheme

data class ControlUiState(
    val isPaused: Boolean = false,
    val repeatCount: Int = 1,
    /** 若設定則覆寫循環次數文字（例如 `02/05` 或 `--`） */
    val repeatDisplay: String? = null,
    val currentStep: Int = 3,
    val totalStep: Int = 25,
    /** 若設定則覆寫步驟文字（例如 `03/25` 或 `--/--`） */
    val stepDisplay: String? = null,
    val timeMs: Long = 0L,
)

@Composable
fun CompactControlBar(
    uiState: ControlUiState,
    modifier: Modifier = Modifier,
    stopEnabled: Boolean = true,
    playPauseEnabled: Boolean = true,
    onStopClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .wrapContentWidth()
            .height(64.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.LightGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. 停止按鈕 (方塊)
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .clickable(enabled = stopEnabled) { onStopClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Stop, // 填滿的方塊
                    contentDescription = "Stop",
                    modifier = Modifier.size(28.dp),
                    tint = Color.DarkGray
                )
            }

            // 垂直分割線
            CustomVerticalDivider()

            // 2. 播放/暫停按鈕 (三角)
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .clickable(enabled = playPauseEnabled) { onPlayPauseClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(32.dp),
                    tint = Color.DarkGray
                )
            }

            // 垂直分割線
            CustomVerticalDivider()

            // 3. 右側資訊區 (循環次數、進度、時間)
            Column(
                modifier =
                    Modifier
                        .width(IntrinsicSize.Max)
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.Gray,
                        )
                        Text(
                            text = uiState.repeatDisplay ?: "${uiState.repeatCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = uiState.stepDisplay ?: "${uiState.currentStep}/${uiState.totalStep}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = formatControlTimeMs(uiState.timeMs),
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    color = Color.DarkGray,
                )
            }
        }
    }
}

internal fun formatControlTimeMs(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = (ms / (1000 * 60 * 60))
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
private fun CustomVerticalDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight(0.6f) // 分割線不要全滿，增加精緻感
            .width(1.dp)
            .background(Color.LightGray.copy(alpha = 0.5f))
    )
}

@Preview(showBackground = true, name = "CompactControlBar (horizontal)")
@Composable
private fun CompactControlBarPreview() {
    ReLCTheme {
        CompactControlBar(
            uiState =
                ControlUiState(
                    isPaused = false,
                    repeatDisplay = "02/05",
                    stepDisplay = "12/25",
                    timeMs = 323_000L,
                ),
        )
    }
}

