package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun CompactNumberInput(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "",
) {
    NoPaddingOutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.isEmpty() || next.all { it.isDigit() }) onValueChange(next)
        },
        modifier = modifier.fillMaxWidth(),
        label = label,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions.Default,
    )
}

@Preview(showBackground = true)
@Composable
private fun CompactNumberInputPreview() {
    ReLCTheme {
        var state by remember { mutableStateOf("42") }
        CompactNumberInput(
            value = state,
            onValueChange = { state = it },
            label = "Ms",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

