package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.defaultPayloadForVerb
import com.xaxaxax.relc.script.simple.parseSwipePayload

@Composable
fun SimpleScriptStepCard(
    index: Int,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
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
                        index = index,
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                        dragHandleModifier = dragHandleModifier,
                    )
                }

                SimpleScriptVerb.SWIPE, SimpleScriptVerb.SWIPE_RAW -> {
                    val swipePayload = runCatching { parseSwipePayload(parsed.payload) }.getOrElse {
                        parseSwipePayload(defaultPayloadForVerb(parsed.verb))
                    }
                    SimpleSwipe(
                        index = index,
                        parsed = parsed,
                        payload = swipePayload,
                        onParsedChange = onParsedChange,
                        useOuterCard = false,
                        dragHandleModifier = dragHandleModifier,
                    )
                }

                SimpleScriptVerb.DELAY -> {
                    SimpleDelayForm(
                        index = index,
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                        dragHandleModifier = dragHandleModifier,
                    )
                }

                SimpleScriptVerb.KEY -> {
                    SimpleKeyForm(
                        index = index,
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                        dragHandleModifier = dragHandleModifier,
                    )
                }

                SimpleScriptVerb.TEXT -> {
                    SimpleTextForm(
                        index = index,
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                        dragHandleModifier = dragHandleModifier,
                    )
                }

                SimpleScriptVerb.SET_DISPLAY -> {
                    SimpleSetDisplayForm(
                        index = index,
                        parsed = parsed,
                        onParsedChange = onParsedChange,
                        dragHandleModifier = dragHandleModifier,
                    )
                }
            }
        }
    }
}