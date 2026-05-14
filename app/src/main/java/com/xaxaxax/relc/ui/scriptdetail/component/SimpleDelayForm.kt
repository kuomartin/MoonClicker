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
fun SimpleDelayForm(
    index: Int,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine?) -> Unit,
) {
    val ms = parsed.payload.trim().toLongOrNull() ?: 0L
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SimpleStepTopLine(
            index = index,
            parsed = parsed,
            onParsedChange = onParsedChange,
            dragHandleModifier = dragHandleModifier,
        ) {
            CompactNumberInput(
                value = ms.toString(),
                label = "Ms",
                modifier = Modifier.width(72.dp),
                onValueChange = {
                    onParsedChange(parsed.copy(payload = (it.toLongOrNull() ?: 0L).toString()))
                },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleDelayFormPreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.DELAY,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "1500",
            )
        )
    }
    ReLCTheme {
        SimpleDelayForm(
            index = 0,
            parsed = parsed,
            onParsedChange = { if (it != null) parsed = it },
            modifier = Modifier.padding(8.dp)
        )
    }
}
