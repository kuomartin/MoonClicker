package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
fun SimpleKeyForm(
    modifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine?) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SimpleStepTopLine(
            parsed = parsed,
            onParsedChange = onParsedChange,
        ) {
            SimpleKeyWireDropdown(wireName = parsed.payload) {
                onParsedChange(parsed.copy(payload = it))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleKeyFormPreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.KEY,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "BACK",
            )
        )
    }
    ReLCTheme {
        SimpleKeyForm(
            parsed = parsed,
            onParsedChange = { if (it != null) parsed = it },
            modifier = Modifier.padding(8.dp)
        )
    }
}
