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
import com.xaxaxax.relc.script.simple.SimpleTapPayload
import com.xaxaxax.relc.script.simple.parseTapPayload
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun SimpleTapForm(
    index: Int,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine?) -> Unit,
) {
    val tap = runCatching { parseTapPayload(parsed.payload) }
        .getOrDefault(SimpleTapPayload(50L, 0, 0))
    val scriptColors = simpleScriptUiColors()
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
                value = tap.durationMs.toString(),
                label = "Ms",
                modifier = Modifier.width(72.dp),
                onValueChange = {
                    val d = it.toLongOrNull() ?: 0L
                    onParsedChange(
                        parsed.copy(
                            payload = tap.copy(durationMs = d).encodeToPayload()
                        )
                    )
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
                value = tap.x.toString(),
                label = "X:",
                modifier = Modifier.weight(1f),
                onValueChange = {
                    val x = it.toIntOrNull() ?: 0
                    onParsedChange(parsed.copy(payload = tap.copy(x = x).encodeToPayload()))
                }
            )
            CompactNumberInput(
                value = tap.y.toString(),
                label = "Y:",
                modifier = Modifier.weight(1f),
                onValueChange = {
                    val y = it.toIntOrNull() ?: 0
                    onParsedChange(parsed.copy(payload = tap.copy(y = y).encodeToPayload()))
                }
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
                payload = "50,540,960",
            )
        )
    }
    ReLCTheme {
        SimpleTapForm(
            index = 0,
            parsed = parsed,
            onParsedChange = { if (it != null) parsed = it },
            modifier = Modifier.padding(8.dp)
        )
    }
}
