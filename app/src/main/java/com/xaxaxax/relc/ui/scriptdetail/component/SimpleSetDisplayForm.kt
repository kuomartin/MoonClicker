package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun SimpleSetDisplayForm(
    index: Int,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine?) -> Unit,
) {
    val id = parsed.payload.trim().toIntOrNull() ?: 0
        SimpleStepTopLine(
            modifier = modifier,
            index = index,
            parsed = parsed,
            onParsedChange = onParsedChange,
            dragHandleModifier = dragHandleModifier,
        ) {
            CompactNumberInput(
                value = id.toString(),
                label = "Id:",
                modifier = Modifier.width(72.dp),
                onValueChange = {
                    onParsedChange(parsed.copy(payload = (it.toIntOrNull() ?: 0).toString()))
                },
            )
        }
    }

@Preview(showBackground = true)
@Composable
private fun SimpleSetDisplayFormPreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.SET_DISPLAY,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "0",
            )
        )
    }
    ReLCTheme {
        SimpleSetDisplayForm(
            index = 0,
            parsed = parsed,
            onParsedChange = { if (it != null) parsed = it },
        )
    }
}
