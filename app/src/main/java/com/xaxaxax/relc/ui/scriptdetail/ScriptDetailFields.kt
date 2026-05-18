package com.xaxaxax.relc.ui.scriptdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xaxaxax.relc.script.LoopMode
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.ui.component.NoPaddingOutlinedTextField
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleScriptStepCard
import com.xaxaxax.relc.ui.scriptdetail.component.simpleScriptUiColors
import com.xaxaxax.relc.ui.theme.ReLCTheme
import com.xaxaxax.relc.update
import kotlin.time.Duration.Companion.milliseconds

private fun LoopMode.sleepMs(): Long = duration.inWholeMilliseconds

private fun LoopMode.repeatCount(): Int = if (count == -1) 3 else count

private fun LoopMode.withSleepMs(ms: Long): LoopMode {
    val dur = ms.coerceAtLeast(0).milliseconds
    return copy(duration = dur)
}

private fun LoopMode.withRepeatCount(c: Int): LoopMode {
    val cnt = c.coerceAtLeast(1)
    return copy(count = cnt)
}

@Composable
fun FormOutlinedField(
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
fun MacroLoopEditor(
    loopMode: LoopMode,
    onLoopModeChange: (LoopMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("迴圈模式", style = MaterialTheme.typography.labelSmall)

        // 直接根據 loopMode 的型別判斷選項
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            // 定義模式名稱
            val modes = listOf("不重複", "無限", "指定次數")

            modes.forEachIndexed { index, label ->
                val isSelected = when (index) {
                    0 -> loopMode.count == 1
                    1 -> loopMode.count == -1
                    else -> loopMode.count > 1
                }

                SegmentedButton(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) return@SegmentedButton
                        val ms = loopMode.sleepMs().milliseconds
                        val currentCount = loopMode.repeatCount().coerceAtLeast(2) // 至少2次，否則就是「不重複」了

                        onLoopModeChange(
                            when (index) {
                                0 -> LoopMode.None
                                1 -> LoopMode.Inf(ms)
                                else -> LoopMode(currentCount, ms)
                            }
                        )
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = { Text(label) }
                )
            }
        }

        // 下方的參數輸入區
        if (loopMode.count != 1) { // 如果不是「不重複」才顯示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 只有在「指定次數」模式（即 count > 1）才顯示次數框
                if (loopMode.count > 1) {
                    NoPaddingOutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = loopMode.repeatCount().toString(),
                        onValueChange = { raw ->
                            raw.toIntOrNull()
                                ?.let { onLoopModeChange(loopMode.withRepeatCount(it.coerceAtLeast(2))) }
                        },
                        label = "次數",
                    )
                }

                NoPaddingOutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = loopMode.sleepMs().toString(),
                    onValueChange = { raw ->
                        raw.toLongOrNull()?.let { onLoopModeChange(loopMode.withSleepMs(it)) }
                    },
                    label = "間隔 (ms)",
                )
            }
        }
    }
}

@Composable
internal fun ScriptConsole(logLines: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    val scriptColors = simpleScriptUiColors()

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
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        }

        AnimatedVisibility(visible = expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(vertical = 8.dp),
                color = scriptColors.scriptLogBackground,
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
                                color = scriptColors.scriptLogText
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleScriptEditor(
    scriptId: String,
    steps: List<ParsedSimpleLine>,
    onStepsChange: (List<ParsedSimpleLine>) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || toIndex !in steps.indices) return
        val newSteps = steps.toMutableList()
        newSteps.add(toIndex, newSteps.removeAt(fromIndex))
        onStepsChange(newSteps)
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("步驟", style = MaterialTheme.typography.titleSmall)
                Text(
                    "格式：verb:次數:次間ms:後延ms:payload — 無法解析的列可手動編輯至合法後即恢復表單。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        itemsIndexed(
            items = steps,
        ) { index: Int, step: ParsedSimpleLine ->
            val isDragging = draggedIndex == index
            val offset by animateFloatAsState(if (isDragging) dragOffset else 0f)

            val dragModifier = Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        draggedIndex = index
                        dragOffset = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount.y

                        val layoutInfo = lazyListState.layoutInfo
                        val draggedItem = layoutInfo.visibleItemsInfo.find { it.index == index + 1 }
                            ?: return@detectDragGestures

                        val targetItem = layoutInfo.visibleItemsInfo.find { item ->
                            val itemIndex = item.index - 1
                            if (itemIndex !in steps.indices || itemIndex == index) return@find false
                            val center = dragOffset + draggedItem.offset + draggedItem.size / 2f
                            center >= item.offset && center <= (item.offset + item.size)
                        }

                        if (targetItem != null) {
                            val newIndex = targetItem.index - 1
                            moveItem(draggedIndex, newIndex)
                            draggedIndex = newIndex
                            dragOffset = 0f
                        }
                    },
                    onDragEnd = {
                        draggedIndex = -1
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        draggedIndex = -1
                        dragOffset = 0f
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = offset
                    }
                    .zIndex(if (isDragging) 1f else 0f)
            ) {
                val parsedResult = runCatching { steps[index] }
                if (parsedResult.isSuccess) {
                    SimpleScriptStepCard(
                        index = index,
                        parsed = parsedResult.getOrThrow(),
                        dragHandleModifier = dragModifier,
                        onParsedChange = { next ->
                            val newSteps = if (next == null)
                                steps.subList(0, index) + steps.subList(index + 1, steps.size)
                            else
                                steps.update(index, next)
                            onStepsChange(newSteps)
                        },
                    )
                } else {
//                    val hint = parsedResult.exceptionOrNull()?.message ?: "parse error"
//                    SimpleScriptRawLine(
//                        index = index,
//                        rawLine = line,
//                        dragHandleModifier = dragModifier,
//                        errorHint = hint,
//                        onRawLineChange = {
//                            lines[index] = it
//                            push()
//                        },
//                        onDelete = {
//                            lines.removeAt(index)
//                            push()
//                        },
//                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(
                    onClick = {
                        onStepsChange(steps + ParsedSimpleLine.Tap)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("新增步驟", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SimpleScriptEditorPreview() {
    ReLCTheme {
        Surface(Modifier.padding(8.dp)) {
            SimpleScriptEditor(
                scriptId = "preview",
                listOf(
                    ParsedSimpleLine.Tap, ParsedSimpleLine.Swipe
                ),
                onStepsChange = {},
            )
        }
    }
}
