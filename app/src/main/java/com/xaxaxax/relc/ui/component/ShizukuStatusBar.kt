package com.xaxaxax.relc.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.R
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.shizuku.ShizukuStatusUiState
import com.xaxaxax.relc.ui.theme.ReLCTheme

/**
 * 顯示在 Displays / Scripts 畫面最上方的一小條 Shizuku 狀態列：
 * 授權狀態 + UserService 連線狀態，並附一顆「取得授權 / 啟動 UserService」按鈕。
 */
@Composable
fun ShizukuStatusBar(
    state: ShizukuStatusUiState,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, tint) = when (state.status) {
        ShizukuConnectionStatus.NOT_AVAILABLE ->
            "Shizuku 未啟動" to MaterialTheme.colorScheme.error
        ShizukuConnectionStatus.NEED_PERMISSION ->
            "需要授權" to MaterialTheme.colorScheme.error
        ShizukuConnectionStatus.DISCONNECTED ->
            "服務未連線" to MaterialTheme.colorScheme.onSurfaceVariant
        ShizukuConnectionStatus.CONNECTED ->
            "服務已連線" to MaterialTheme.colorScheme.primary
    }
    val actionLabel = when (state.status) {
        ShizukuConnectionStatus.NEED_PERMISSION -> "授權"
        ShizukuConnectionStatus.NOT_AVAILABLE, ShizukuConnectionStatus.DISCONNECTED -> "連線"
        ShizukuConnectionStatus.CONNECTED -> null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_shizuku_icon),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
            if (actionLabel != null) {
                TextButton(onClick = onActionClick) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ShizukuStatusBarPreviewNotAvailable() {
    ReLCTheme {
        ShizukuStatusBar(
            state = ShizukuStatusUiState(isAvailable = false, hasPermission = false, isConnected = false),
            onActionClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ShizukuStatusBarPreviewNeedPermission() {
    ReLCTheme {
        ShizukuStatusBar(
            state = ShizukuStatusUiState(isAvailable = true, hasPermission = false, isConnected = false),
            onActionClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ShizukuStatusBarPreviewConnected() {
    ReLCTheme {
        ShizukuStatusBar(
            state = ShizukuStatusUiState(isAvailable = true, hasPermission = true, isConnected = true),
            onActionClick = {},
        )
    }
}
