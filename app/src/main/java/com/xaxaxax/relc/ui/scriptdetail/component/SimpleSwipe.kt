package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.SimpleSwipePayload
import com.xaxaxax.relc.script.simple.defaultPayloadForVerb
import com.xaxaxax.relc.script.simple.encodeToPayload
import com.xaxaxax.relc.script.simple.parseSwipePayload
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun SimpleSwipe(
    modifier: Modifier = Modifier,
    parsed: ParsedSimpleLine,
    payload: SimpleSwipePayload,
    onParsedChange: (ParsedSimpleLine?) -> Unit,
    useOuterCard: Boolean = true,
) {
    fun emitSwipe(next: SimpleSwipePayload) {
        onParsedChange(parsed.copy(payload = next.encodeToPayload()))
    }

    var pointsExpanded by remember { mutableStateOf(payload.points.size <= 5) }

    val scriptColors = simpleScriptUiColors()
    val inner: @Composable () -> Unit = {
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SimpleStepTopLine(
                parsed = parsed,
                onParsedChange = onParsedChange,
            ) {
                CompactNumberInput(
                    value = payload.durationMs.toString(),
                    label = "Ms",
                    modifier = Modifier.width(72.dp),
                    onValueChange = {
                        emitSwipe(payload.copy(durationMs = it.toLongOrNull() ?: 0L))
                    },
                )
            }
            HorizontalDivider(color = scriptColors.divider)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pointsExpanded = !pointsExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Points (${payload.points.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (pointsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (pointsExpanded) {
                payload.points.forEachIndexed { index, (x, y) ->
                    key(index) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "P${index + 1}:",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(32.dp)
                            )

                            CompactNumberInput(
                                value = x.toString(),
                                label = "X:",
                                modifier = Modifier.weight(1f),
                                onValueChange = { newX ->
                                    val newPoints = payload.points.toMutableList()
                                    newPoints[index] = (newX.toIntOrNull() ?: 0) to y
                                    emitSwipe(payload.copy(points = newPoints))
                                }
                            )

                            CompactNumberInput(
                                value = y.toString(),
                                label = "Y:",
                                modifier = Modifier.weight(1f),
                                onValueChange = { newY ->
                                    val newPoints = payload.points.toMutableList()
                                    newPoints[index] = x to (newY.toIntOrNull() ?: 0)
                                    emitSwipe(payload.copy(points = newPoints))
                                }
                            )

                            val canRemovePoint = payload.points.size > 2
                            IconButton(
                                onClick = {
                                    if (!canRemovePoint) return@IconButton
                                    val newPoints =
                                        payload.points.toMutableList().apply { removeAt(index) }
                                    emitSwipe(payload.copy(points = newPoints))
                                },
                                enabled = canRemovePoint,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "刪除點",
                                    tint = if (canRemovePoint) {
                                        scriptColors.destructive
                                    } else {
                                        scriptColors.destructiveMuted
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(
                        onClick = {
                            emitSwipe(payload.copy(points = payload.points + (0 to 0)))
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Add Point", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (useOuterCard) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(4.dp),
            colors = CardDefaults.cardColors(containerColor = scriptColors.stepCardContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            inner()
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            inner()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSimpleSwipe() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.SWIPE,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "500,0,0,100,100",
            )
        )
    }
    val swipePayload = runCatching { parseSwipePayload(parsed.payload) }.getOrElse {
        parseSwipePayload(defaultPayloadForVerb(SimpleScriptVerb.SWIPE))
    }
    ReLCTheme {
        SimpleSwipe(
            parsed = parsed,
            payload = swipePayload,
            onParsedChange = {
                if (it != null) {
                    parsed = it
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSimpleSwipeEmbedded() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.SWIPE,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "300,10,10,200,200,200,400",
            )
        )
    }
    val swipePayload = runCatching { parseSwipePayload(parsed.payload) }.getOrElse {
        parseSwipePayload(defaultPayloadForVerb(SimpleScriptVerb.SWIPE))
    }
    ReLCTheme {
        SimpleSwipe(
            parsed = parsed,
            payload = swipePayload,
            onParsedChange = {
                if (it != null) {
                    parsed = it
                }
            },
            useOuterCard = false,
        )
    }
}
