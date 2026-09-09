package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.input.SimplePhysicalKey

@Composable
fun RowScope.SimpleKeyWireDropdown(
    wireName: String,
    onSelectWire: (String) -> Unit,
) {
    val parsedKey = runCatching { SimplePhysicalKey.parse(wireName) }.getOrNull()
    var expanded by remember { mutableStateOf(false) }
    val label = parsedKey?.wireName ?: wireName.ifBlank { "…" }
    Box {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SimplePhysicalKey.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.wireName) },
                    onClick = {
                        onSelectWire(k.wireName)
                        expanded = false
                    }
                )
            }
        }
    }
}