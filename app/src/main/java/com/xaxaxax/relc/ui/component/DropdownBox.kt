package com.xaxaxax.relc.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.ui.theme.ReLCTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownBox(
    values: List<T>,
    value: T?,
    transform: (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "請選擇",
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            // 如果 value 是 null，就顯示空字串，讓 Label 浮起來
            value = value?.let { transform(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize() // 強制選單寬度與 TextField 對齊
        ) {
            values.forEach { item ->
                DropdownMenuItem(
                    text = { Text(text = transform(item)) },
                    onClick = {
                        onChange(item)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview(showBackground = true)
private fun DropdownBoxPreview() {
    val values = listOf(0, 1, 2, 3, 4, 5, 100)
    var value by remember { mutableStateOf<Int?>(0) }

    ReLCTheme() { // 確保有 Theme
        Surface(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column { // 放在 Column 裡避免填滿整個畫面看不到東西
                DropdownBox(
                    values = values,
                    value = value,
                    transform = { it.toString() },
                    onChange = { value = it }
                )
            }
        }
    }
}
