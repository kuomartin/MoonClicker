package com.xaxaxax.moonclicker.ui.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.core.DisplayConfig
import com.xaxaxax.moonclicker.core.DisplayInfo
import com.xaxaxax.moonclicker.script.ScriptTarget

/**
 * 「在指定顯示器執行」的選單（issue #5）：本機螢幕、已建立的虛擬顯示，最上面可以直接
 * 建一個新的——不用先跳去 Displays 頁。
 */
@Composable
fun TargetPicker(
    displays: List<DisplayInfo>,
    newDisplayConfig: DisplayConfig,
    onPick: (ScriptTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.target_picker_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                item {
                    TargetRow(
                        title = stringResource(R.string.target_picker_new),
                        subtitle = "${newDisplayConfig.width} × ${newDisplayConfig.height}",
                        onClick = { onPick(ScriptTarget.NewVirtual(newDisplayConfig)) },
                    )
                    HorizontalDivider()
                }
                items(displays, key = { it.displayId }) { display ->
                    TargetRow(
                        title = stringResource(R.string.target_picker_virtual, display.displayId),
                        subtitle = stringResource(
                            R.string.target_picker_virtual_note, display.width, display.height
                        ),
                        onClick = { onPick(ScriptTarget.ExistingVirtual(display.displayId)) },
                    )
                }
                item {
                    HorizontalDivider()
                    TargetRow(
                        title = stringResource(R.string.target_picker_physical),
                        // 實體螢幕沒有影格來源，vision.* 在那裡會直接報錯——先說清楚。
                        subtitle = stringResource(R.string.target_picker_physical_note),
                        onClick = { onPick(ScriptTarget.PhysicalDisplay) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun TargetRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
