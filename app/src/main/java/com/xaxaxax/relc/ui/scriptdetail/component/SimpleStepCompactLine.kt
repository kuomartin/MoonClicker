package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.xaxaxax.relc.script.simple.SimplePhysicalKey
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.defaultPayloadForVerb
import com.xaxaxax.relc.ui.theme.ReLCTheme

internal fun SimpleScriptVerb.menuLabel(): String =
    when (this) {
        SimpleScriptVerb.TAP -> "Tap"
        SimpleScriptVerb.SWIPE -> "Swipe"
        SimpleScriptVerb.DELAY -> "Delay"
        SimpleScriptVerb.KEY -> "Key"
        SimpleScriptVerb.TEXT -> "Text"
        SimpleScriptVerb.SET_DISPLAY -> "Display"
    }

@Composable
fun SimpleVerbMenuAnchor(
    currentVerb: SimpleScriptVerb,
    onSelectVerb: (SimpleScriptVerb) -> Unit,
) {
    val scriptColors = simpleScriptUiColors()
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.wrapContentWidth()) {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = currentVerb.menuLabel(),
                style = MaterialTheme.typography.titleSmall,
                color = scriptColors.verbMenuLabel,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = scriptColors.verbMenuLabel,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SimpleScriptVerb.entries.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.menuLabel()) },
                    onClick = {
                        expanded = false
                        onSelectVerb(v)
                    }
                )
            }
        }
    }
}

@Composable
fun SimpleStepTopLine(
    parsed: ParsedSimpleLine,
    onParsedChange: (ParsedSimpleLine) -> Unit,
    onChangeVerb: (SimpleScriptVerb?) -> Unit,
    modifier: Modifier = Modifier,
    middle: @Composable RowScope.() -> Unit = {},
) {
    val scriptColors = simpleScriptUiColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SimpleVerbMenuAnchor(
                currentVerb = parsed.verb,
                onSelectVerb = { v ->
                    if (v != parsed.verb) {
                        onParsedChange(parsed.copy(verb = v, payload = defaultPayloadForVerb(v)))
                    }
                },
            )
            middle()
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactNumberInput(
                value = parsed.repeatCount.toString(),
                label = "R",
                modifier = Modifier.width(50.dp),
                onValueChange = {
                    val n = it.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    onParsedChange(parsed.copy(repeatCount = n))
                },
            )
            CompactNumberInput(
                value = parsed.delayBetweenRepeatsMs.toString(),
                label = "B",
                modifier = Modifier.width(50.dp),
                onValueChange = {
                    val n = it.toLongOrNull() ?: 0L
                    onParsedChange(parsed.copy(delayBetweenRepeatsMs = n))
                },
            )
            CompactNumberInput(
                value = parsed.delayAfterStepMs.toString(),
                label = "A",
                modifier = Modifier.width(50.dp),
                onValueChange = {
                    val n = it.toLongOrNull() ?: 0L
                    onParsedChange(parsed.copy(delayAfterStepMs = n))
                },
            )
            IconButton(
                onClick = { onChangeVerb(null) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "刪除步驟",
                    tint = scriptColors.destructive,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
fun RowScope.SimpleKeyWireDropdown(
    wireName: String,
    onSelectWire: (String) -> Unit,
) {
    val parsedKey = runCatching { SimplePhysicalKey.parse(wireName) }.getOrNull()
    var expanded by remember { mutableStateOf(false) }
    val label = parsedKey?.wireName ?: wireName.ifBlank { "…" }
    Box {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SimplePhysicalKey.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.wireName) },
                    onClick = {
                        onSelectWire(k.wireName)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleVerbMenuAnchorPreview() {
    var v by remember { mutableStateOf(SimpleScriptVerb.TAP) }
    ReLCTheme {
        SimpleVerbMenuAnchor(
            currentVerb = v,
            onSelectVerb = { v = it },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleStepTopLinePreview() {
    var parsed by remember {
        mutableStateOf(
            ParsedSimpleLine(
                verb = SimpleScriptVerb.DELAY,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = "500",
            )
        )
    }
    val update = { next: ParsedSimpleLine -> parsed = next }
    ReLCTheme {
        SimpleStepTopLine(
            parsed = parsed,
            onParsedChange = update,
            onChangeVerb = {},
            modifier = Modifier.padding(8.dp),
        ) {
            CompactNumberInput(
                value = parsed.payload.trim().toLongOrNull()?.toString().orEmpty().ifEmpty { "0" },
                label = "Ms",
                modifier = Modifier.width(96.dp),
                onValueChange = {
                    update(parsed.copy(payload = (it.toLongOrNull() ?: 0L).toString()))
                },
            )
        }
    }
}
