package com.xaxaxax.moonclicker.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xaxaxax.moonclicker.R

/**
 * 遵循 Android 權限設計最佳實踐（Permission Rationale）：
 * 在向系統請求權限或跳轉系統設定前，先向使用者清晰說明需要該權限的理由與對應功能。
 *
 * [shizukuActionLabel]/[onShizukuAction] 非 null 時，在 [onConfirm] 之上多渲染一顆按鈕，
 * 讓已連上 Shizuku 的使用者可以直接透過 Shizuku 取得權限，而不必跳系統設定頁。
 */
@Composable
fun PermissionRationaleDialog(
    title: String,
    description: String,
    icon: Painter,
    confirmText: String = stringResource(R.string.permission_action_proceed),
    dismissText: String = stringResource(R.string.permission_action_cancel),
    shizukuActionLabel: String? = null,
    onShizukuAction: (() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (shizukuActionLabel != null && onShizukuAction != null) {
                    TextButton(
                        onClick = {
                            onShizukuAction()
                            onDismiss()
                        }
                    ) {
                        Text(shizukuActionLabel)
                    }
                }
                Button(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    }
                ) {
                    Text(confirmText)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}
