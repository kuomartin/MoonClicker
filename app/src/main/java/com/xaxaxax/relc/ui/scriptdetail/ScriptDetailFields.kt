package com.xaxaxax.relc.ui.scriptdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.script.LoopMode
import com.xaxaxax.relc.script.ScriptCodeType
import com.xaxaxax.relc.script.simple.SimpleScriptBodyJson
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import com.xaxaxax.relc.script.simple.SimpleStepJson
import com.xaxaxax.relc.script.simple.SimpleStepKind
import com.xaxaxax.relc.ui.component.DropdownBox
import kotlin.time.Duration.Companion.milliseconds

private enum class MacroLoopKind(val label: String) {
    NONE("單次"),
    INF("無限"),
    REPEAT("重複"),
}

private fun LoopMode.toMacroKind(): MacroLoopKind = when (this) {
    LoopMode.None -> MacroLoopKind.NONE
    is LoopMode.Inf -> MacroLoopKind.INF
    is LoopMode.Repeat -> MacroLoopKind.REPEAT
}

private fun LoopMode.sleepMs(): Long = when (this) {
    is LoopMode.Inf -> duration.inWholeMilliseconds
    is LoopMode.Repeat -> duration.inWholeMilliseconds
    else -> 0L
}

private fun LoopMode.repeatCount(): Int = when (this) {
    is LoopMode.Repeat -> count
    else -> 3
}

private fun LoopMode.withSleepMs(ms: Long): LoopMode {
    val dur = ms.coerceAtLeast(0).milliseconds
    return when (this) {
        LoopMode.None -> LoopMode.None
        is LoopMode.Inf -> LoopMode.Inf(dur)
        is LoopMode.Repeat -> LoopMode.Repeat(count, dur)
    }
}

private fun LoopMode.withRepeatCount(c: Int): LoopMode {
    val cnt = c.coerceAtLeast(1)
    return when (this) {
        is LoopMode.Repeat -> LoopMode.Repeat(cnt, duration)
        else -> LoopMode.Repeat(cnt, sleepMs().coerceAtLeast(0).milliseconds)
    }
}

@Composable
internal fun FormOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = singleLine && minLines <= 1,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptTypeDropdown(
    type: ScriptCodeType,
    onTypeSelected: (ScriptCodeType) -> Unit,
) {
    DropdownBox(
        values = ScriptCodeType.entries,
        value = type,
        transform = { it.name },
        label = "腳本類型",
        onChange = onTypeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MacroLoopEditor(
    loopMode: LoopMode,
    onLoopModeChange: (LoopMode) -> Unit,
) {
    val kind = loopMode.toMacroKind()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownBox(
            values = MacroLoopKind.entries,
            value = kind,
            transform = { it.label },
            label = "Macro 迴圈",
            onChange = { newKind ->
                val ms = loopMode.sleepMs()
                val cnt = loopMode.repeatCount()
                onLoopModeChange(
                    when (newKind) {
                        MacroLoopKind.NONE -> LoopMode.None
                        MacroLoopKind.INF -> LoopMode.Inf(ms.coerceAtLeast(0).milliseconds)
                        MacroLoopKind.REPEAT -> LoopMode.Repeat(
                            cnt,
                            ms.coerceAtLeast(0).milliseconds,
                        )
                    },
                )
            },
        )
        if (kind != MacroLoopKind.NONE) {
            FormOutlinedField(
                value = loopMode.sleepMs().toString(),
                onValueChange = { raw ->
                    raw.toLongOrNull()?.let { onLoopModeChange(loopMode.withSleepMs(it)) }
                },
                label = "輪替間隔 (ms)",
            )
        }
        if (kind == MacroLoopKind.REPEAT) {
            FormOutlinedField(
                value = loopMode.repeatCount().toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.let { onLoopModeChange(loopMode.withRepeatCount(it)) }
                },
                label = "重複次數",
            )
        }
    }
}

@Composable
internal fun ScriptConsole(logLines: List<String>) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Console",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!expanded && logLines.isNotEmpty()) {
                    Text(
                        " - ${logLines.last()}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(vertical = 8.dp),
                color = Color.Black,
                shape = MaterialTheme.shapes.small,
            ) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp),
                    reverseLayout = true
                ) {
                    items(logLines.reversed()) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color.Green
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SimpleScriptEditor(
    scriptId: String,
    code: String,
    onBodyChanged: (SimpleScriptBodyJson) -> Unit,
) {
    val steps = remember { mutableStateListOf<SimpleStepJson>() }
    var defaultDisplayId by remember(scriptId) { mutableIntStateOf(0) }
    val latestOnBodyChanged by rememberUpdatedState(onBodyChanged)

    LaunchedEffect(scriptId, code) {
        val body = SimpleScriptCodec.decodeOrNull(code) ?: SimpleScriptBodyJson()
        if (steps.toList() == body.steps && defaultDisplayId == body.defaultDisplayId) return@LaunchedEffect
        steps.clear()
        steps.addAll(body.steps)
        defaultDisplayId = body.defaultDisplayId
    }

    fun push() {
        latestOnBodyChanged(
            SimpleScriptBodyJson(
                defaultDisplayId = defaultDisplayId,
                steps = steps.toList(),
            ),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormOutlinedField(
            value = defaultDisplayId.toString(),
            onValueChange = { raw ->
                raw.toIntOrNull()?.let {
                    defaultDisplayId = it
                    push()
                }
            },
            label = "預設 display id",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("步驟", style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = {
                steps.add(
                    SimpleStepJson(
                        kind = SimpleStepKind.TAP,
                        x = 540,
                        y = 960,
                    ),
                )
                push()
            }) {
                Icon(Icons.Default.Add, contentDescription = "新增步驟")
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            steps.forEachIndexed { index, step ->
                SimpleStepCard(
                    step = step,
                    onChange = { newStep ->
                        steps[index] = newStep
                        push()
                    },
                    onRemove = {
                        steps.removeAt(index)
                        push()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimpleStepCard(
    step: SimpleStepJson,
    onChange: (SimpleStepJson) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                DropdownBox(
                    values = SimpleStepKind.entries.toList(),
                    value = step.kind,
                    transform = { it.name },
                    label = "指令",
                    modifier = Modifier.weight(1f),
                    onChange = { onChange(step.copy(kind = it)) },
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "刪除")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormOutlinedField(
                    value = step.repeatCount.toString(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.takeIf { it >= 1 }
                            ?.let { onChange(step.copy(repeatCount = it)) }
                    },
                    label = "次數",
                    modifier = Modifier.weight(1f),
                )
                FormOutlinedField(
                    value = step.delayBetweenRepeatsMs.toString(),
                    onValueChange = { raw ->
                        raw.toLongOrNull()?.let { onChange(step.copy(delayBetweenRepeatsMs = it)) }
                    },
                    label = "次間 ms",
                    modifier = Modifier.weight(1f),
                )
                FormOutlinedField(
                    value = step.delayAfterStepMs.toString(),
                    onValueChange = { raw ->
                        raw.toLongOrNull()?.let { onChange(step.copy(delayAfterStepMs = it)) }
                    },
                    label = "後延 ms",
                    modifier = Modifier.weight(1f),
                )
            }

            FormOutlinedField(
                value = step.displayId?.toString().orEmpty(),
                onValueChange = { raw ->
                    if (raw.isBlank()) onChange(step.copy(displayId = null))
                    else raw.toIntOrNull()?.let { onChange(step.copy(displayId = it)) }
                },
                label = "Display 覆寫（可選）",
            )

            SimpleStepKindFields(step, onChange)
        }
    }
}

@Composable
private fun SimpleStepKindFields(
    step: SimpleStepJson,
    onChange: (SimpleStepJson) -> Unit,
) {
    when (step.kind) {
        SimpleStepKind.TAP -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormOutlinedField(
                    value = step.x?.toString().orEmpty(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { onChange(step.copy(x = it)) }
                    },
                    label = "x",
                    modifier = Modifier.weight(1f),
                )
                FormOutlinedField(
                    value = step.y?.toString().orEmpty(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { onChange(step.copy(y = it)) }
                    },
                    label = "y",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SimpleStepKind.SWIPE -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormOutlinedField(
                    value = step.x1?.toString().orEmpty(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { onChange(step.copy(x1 = it)) }
                    },
                    label = "x1",
                    modifier = Modifier.weight(1f),
                )
                FormOutlinedField(
                    value = step.y1?.toString().orEmpty(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { onChange(step.copy(y1 = it)) }
                    },
                    label = "y1",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormOutlinedField(
                    value = step.x2?.toString().orEmpty(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { onChange(step.copy(x2 = it)) }
                    },
                    label = "x2",
                    modifier = Modifier.weight(1f),
                )
                FormOutlinedField(
                    value = step.y2?.toString().orEmpty(),
                    onValueChange = { raw ->
                        raw.toIntOrNull()?.let { onChange(step.copy(y2 = it)) }
                    },
                    label = "y2",
                    modifier = Modifier.weight(1f),
                )
            }
            FormOutlinedField(
                value = step.durationMs?.toString().orEmpty(),
                onValueChange = { raw ->
                    raw.toLongOrNull()?.let { onChange(step.copy(durationMs = it)) }
                },
                label = "durationMs",
            )
        }

        SimpleStepKind.DELAY -> {
            FormOutlinedField(
                value = step.delayMs?.toString().orEmpty(),
                onValueChange = { raw ->
                    raw.toLongOrNull()?.let { onChange(step.copy(delayMs = it)) }
                },
                label = "delayMs",
            )
        }

        SimpleStepKind.KEY -> {
            FormOutlinedField(
                value = step.key.orEmpty(),
                onValueChange = { onChange(step.copy(key = it.ifBlank { null })) },
                label = "按鍵（BACK, POWER, …）",
            )
        }

        SimpleStepKind.TEXT -> {
            FormOutlinedField(
                value = step.text.orEmpty(),
                onValueChange = { onChange(step.copy(text = it.ifBlank { null })) },
                label = "文字（尚未執行）",
            )
        }
    }
}

@Composable
internal fun AlwaysRunCleanRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text("永遠執行 clean", style = MaterialTheme.typography.bodyMedium)
    }
}
