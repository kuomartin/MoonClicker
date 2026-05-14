package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.xaxaxax.relc.script.simple.defaultPayloadForVerb
import com.xaxaxax.relc.script.simple.parseSwipePayload
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun SimpleScriptStepCard(
    modifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine?) -> Unit,
) {
    val scriptColors = simpleScriptUiColors()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = scriptColors.stepCardContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (parsed.verb) {
                SimpleScriptVerb.TAP -> {
                    SimpleTapForm(
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                    )
                }

                SimpleScriptVerb.SWIPE, SimpleScriptVerb.SWIPE_RAW -> {
                    val swipePayload = runCatching { parseSwipePayload(parsed.payload) }.getOrElse {
                        parseSwipePayload(defaultPayloadForVerb(parsed.verb))
                    }
                    SimpleSwipe(
                        parsed = parsed,
                        payload = swipePayload,
                        onParsedChange = onParsedChange,
                        useOuterCard = false,
                    )
                }

                SimpleScriptVerb.DELAY -> {
                    SimpleDelayForm(
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                    )
                }

                SimpleScriptVerb.KEY -> {
                    SimpleKeyForm(
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                    )
                }

                SimpleScriptVerb.TEXT -> {
                    SimpleTextForm(
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                    )
                }

                SimpleScriptVerb.SET_DISPLAY -> {
                    SimpleSetDisplayForm(
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleScriptStepCardTapPreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.TAP,
                repeatCount = 2,
                delayBetweenRepeatsMs = 100,
                delayAfterStepMs = 50,
                payload = "50,540,960",
            )
        )
    }
    ReLCTheme {
        SimpleScriptStepCard(
            parsed = parsed,
            onParsedChange = { if (it != null) parsed = it },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleScriptStepCardSwipePreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.SWIPE,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "500,0,0,200,200",
            )
        )
    }
    ReLCTheme {
        SimpleScriptStepCard(
            parsed = parsed,
            onParsedChange = { if (it != null) parsed = it },
        )
    }
}
