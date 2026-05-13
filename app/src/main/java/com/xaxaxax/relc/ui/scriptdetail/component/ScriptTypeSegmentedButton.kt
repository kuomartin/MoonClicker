package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.script.ScriptCodeType

@Composable
fun ScriptTypeSegmentedButton(
    currentType: ScriptCodeType,
    onTypeChange: (ScriptCodeType) -> Unit
) {
    Column {
        Text("腳本類別", style = MaterialTheme.typography.labelSmall)
        // 定義選項列表，與 ScriptCodeType 映射
        val options = listOf(ScriptCodeType.LUA, ScriptCodeType.SIMPLE)

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            options.forEachIndexed { index, type ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    onClick = {
                        if (currentType != type) {
                            onTypeChange(type)
                        }
                    },
                    selected = currentType == type,
                    label = {
                        Text(
                            text = when (type) {
                                ScriptCodeType.LUA -> "Lua 腳本"
                                ScriptCodeType.SIMPLE -> "簡易腳本"
                            }
                        )
                    },
                    // 如果你想讓介面更精緻，可以根據類型加入圖示
                    icon = {
                        SegmentedButtonDefaults.Icon(active = currentType == type) {
                            Icon(
                                imageVector = if (type == ScriptCodeType.LUA) Icons.Default.Code else Icons.Default.TouchApp,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Preview
@Composable
private fun ScriptTypeSegmentedButtonPreview() {
    val (type, setType) = remember { mutableStateOf(ScriptCodeType.LUA) }
    ScriptTypeSegmentedButton(
        currentType = type,
        onTypeChange = setType
    )
}