package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.parseTapPayload
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun SimpleTapForm(
    modifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine) -> Unit,
    onChangeVerb: (SimpleScriptVerb?) -> Unit,
) {
    val (tx, ty) = runCatching { parseTapPayload(parsed.payload) }.getOrDefault(0 to 0)
    val scriptColors = simpleScriptUiColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SimpleStepTopLine(
            parsed = parsed,
            onParsedChange = onParsedChange,
            onChangeVerb = onChangeVerb,
        ) {
            CompactNumberInput(
                value = parsed.delayAfterStepMs.toString(),
                label = "Ms",
                modifier = Modifier.width(72.dp),
                onValueChange = {
                    onParsedChange(parsed.copy(delayAfterStepMs = it.toLongOrNull() ?: 0L))
                },
            )
        }
        HorizontalDivider(color = scriptColors.divider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactNumberInput(
                value = tx.toString(),
                label = "X:",
                modifier = Modifier.weight(1f),
                onValueChange = { onParsedChange(parsed.copy(payload = "${it.toIntOrNull() ?: 0},$ty")) }
            )
            CompactNumberInput(
                value = ty.toString(),
                label = "Y:",
                modifier = Modifier.weight(1f),
                onValueChange = { onParsedChange(parsed.copy(payload = "$tx,${it.toIntOrNull() ?: 0}")) }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleTapFormPreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.TAP,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "540,960",
            )
        )
    }
    ReLCTheme {
        SimpleTapForm(
            parsed = parsed,
            onParsedChange = { parsed = it },
            onChangeVerb = {},
            modifier = Modifier.padding(8.dp)
        )
    }
}
