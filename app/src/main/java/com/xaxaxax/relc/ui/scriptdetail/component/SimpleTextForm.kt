package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
fun SimpleTextForm(
    modifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine?) -> Unit,
) {
    val scriptColors = simpleScriptUiColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SimpleStepTopLine(
            parsed = parsed,
            onParsedChange = onParsedChange,
        ) {}
        HorizontalDivider(color = scriptColors.divider)
        OutlinedTextField(
            value = parsed.payload,
            onValueChange = { onParsedChange(parsed.copy(payload = it)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 2,
            singleLine = false,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleTextFormPreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.TEXT,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "hello:world",
            )
        )
    }
    ReLCTheme {
        SimpleTextForm(
            parsed = parsed,
            onParsedChange = { if (it != null) parsed = it },
            modifier = Modifier.padding(8.dp)
        )
    }
}
