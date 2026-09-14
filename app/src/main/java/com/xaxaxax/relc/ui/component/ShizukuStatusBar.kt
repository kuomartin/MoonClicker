package com.xaxaxax.relc.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import com.xaxaxax.relc.ui.theme.ReLCTheme

/**
 * 顯示在 Displays / Scripts 畫面最上方的一小條 Shizuku 狀態列：
 * 授權狀態 + UserService 連線狀態，並附一顆「取得授權 / 啟動 UserService」按鈕。
 *
 * 連線是自動的，按鈕只在自動連線沒能把狀態推到 CONNECTED 時才需要。
 */
@Composable
fun ShizukuStatusBar(
    status: ShizukuConnectionStatus,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, tint) = shizukuStatusAppearance(status)
    val actionLabel = when (status) {
        ShizukuConnectionStatus.NEED_PERMISSION -> "授權"
        ShizukuConnectionStatus.NOT_AVAILABLE, ShizukuConnectionStatus.DISCONNECTED -> "連線"
        ShizukuConnectionStatus.CONNECTING, ShizukuConnectionStatus.CONNECTED -> null
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
            if (status == ShizukuConnectionStatus.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
            } else if (actionLabel != null) {
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
            status = ShizukuConnectionStatus.NOT_AVAILABLE,
            onActionClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ShizukuStatusBarPreviewNeedPermission() {
    ReLCTheme {
        ShizukuStatusBar(
            status = ShizukuConnectionStatus.NEED_PERMISSION,
            onActionClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ShizukuStatusBarPreviewConnecting() {
    ReLCTheme {
        ShizukuStatusBar(
            status = ShizukuConnectionStatus.CONNECTING,
            onActionClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ShizukuStatusBarPreviewConnected() {
    ReLCTheme {
        ShizukuStatusBar(
            status = ShizukuConnectionStatus.CONNECTED,
            onActionClick = {},
        )
    }
}
