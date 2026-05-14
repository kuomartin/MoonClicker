package com.xaxaxax.relc.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xaxaxax.relc.script.ScriptCodeType
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SIMPLE_SCRIPT_DEFAULT_STEP_LINE
import com.xaxaxax.relc.script.simple.SimpleScriptBodyJson
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.defaultPayloadForVerb
import com.xaxaxax.relc.script.simple.encodeToLine
import com.xaxaxax.relc.script.simple.parseSimpleScriptLine
import com.xaxaxax.relc.script.simple.parseSwipePayload
import com.xaxaxax.relc.ui.component.NoPaddingOutlinedTextField
import com.xaxaxax.relc.ui.component.Section
import com.xaxaxax.relc.ui.scriptdetail.MacroLoopEditor
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleDelayForm
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleKeyForm
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleScriptRawLine
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleSetDisplayForm
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleSwipe
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleTapForm
import com.xaxaxax.relc.ui.scriptdetail.component.SimpleTextForm
import com.xaxaxax.relc.ui.scriptdetail.component.simpleScriptUiColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedScriptEditor(
    config: ScriptConfig,
    isRunning: Boolean,
    onNavigateBack: () -> Unit,
    onUpdateConfig: ((ScriptConfig) -> ScriptConfig) -> Unit,
    onSave: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val lines = remember { mutableStateListOf<String>() }
    LaunchedEffect(config.id, config.code) {
        if (config.type == ScriptCodeType.SIMPLE) {
            val body = SimpleScriptCodec.decodeOrNull(config.code) ?: SimpleScriptBodyJson()
            if (lines.toList() != body.steps) {
                lines.clear()
                lines.addAll(body.steps)
            }
        }
    }

    fun pushSteps() {
        onUpdateConfig {
            it.copy(code = SimpleScriptCodec.encode(SimpleScriptBodyJson(steps = lines.toList())))
        }
    }

    val lazyListState = rememberLazyListState()
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || toIndex !in lines.indices) return
        lines.add(toIndex, lines.removeAt(fromIndex))
        pushSteps()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config.name) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (isRunning) onStop() else {
                            onSave()
                            onPlay()
                        }
                    }) {
                        Icon(
                            if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isRunning) "Stop" else "Play",
                            tint = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onSave) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                Section(name = "基本資訊") {
                    NoPaddingOutlinedTextField(
                        value = config.name,
                        onValueChange = { v -> onUpdateConfig { it.copy(name = v) } },
                        label = "名稱",
                    )
                    NoPaddingOutlinedTextField(
                        value = config.description.orEmpty(),
                        onValueChange = { v -> onUpdateConfig { it.copy(description = v) } },
                        label = "描述",
                        singleLine = false,
                    )
                }
            }

            item {
                Section(name = "執行策略") {
                    MacroLoopEditor(
                        loopMode = config.loopMode,
                        onLoopModeChange = { mode ->
                            onUpdateConfig { it.copy(loopMode = mode) }
                        },
                    )
                }
            }

            if (config.type == ScriptCodeType.SIMPLE) {
                item {
                    Section(name = "步驟內容") {
                        Text(
                            "格式：verb:次數:次間ms:後延ms:payload — 無法解析的列可手動編輯至合法後即恢復表單。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                itemsIndexed(items = lines, key = { i, _ -> "step-$i" }) { index, line ->
                    val isDragging = draggedIndex == index
                    val offset by animateFloatAsState(
                        if (isDragging) dragOffset else 0f,
                        label = "drag"
                    )

                    val dragModifier = Modifier.pointerInput(index) {
                        detectDragGestures(
                            onDragStart = {
                                draggedIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y

                                val layoutInfo = lazyListState.layoutInfo
                                val draggedItem =
                                    layoutInfo.visibleItemsInfo.find { it.key == "step-$index" }
                                        ?: return@detectDragGestures

                                val targetItem = layoutInfo.visibleItemsInfo.find { item ->
                                    val itemKey = item.key as? String ?: ""
                                    if (!itemKey.startsWith("step-") || item.key == "step-$index") return@find false
                                    val center =
                                        dragOffset + draggedItem.offset + draggedItem.size / 2f
                                    center >= item.offset && center <= (item.offset + item.size)
                                }

                                if (targetItem != null) {
                                    val targetKey = targetItem.key as String
                                    val newIndex = targetKey.substringAfter("step-").toInt()
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
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        val parsedResult = runCatching { parseSimpleScriptLine(line) }
                        if (parsedResult.isSuccess) {
                            val parsed = parsedResult.getOrThrow()
                            val onParsedChange: (ParsedSimpleLine?) -> Unit = { next ->
                                if (next == null)
                                    lines.removeAt(index)
                                else
                                    lines[index] = next.encodeToLine()
                                pushSteps()
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                when (parsed.verb) {
                                    SimpleScriptVerb.TAP -> {
                                        SimpleTapForm(
                                            index = index,
                                            parsed = parsed,
                                            onParsedChange = onParsedChange,
                                            dragHandleModifier = dragModifier,
                                        )
                                    }

                                    SimpleScriptVerb.SWIPE, SimpleScriptVerb.SWIPE_RAW -> {
                                        val swipePayload =
                                            runCatching { parseSwipePayload(parsed.payload) }.getOrElse {
                                                parseSwipePayload(defaultPayloadForVerb(parsed.verb))
                                            }
                                        SimpleSwipe(
                                            index = index,
                                            parsed = parsed,
                                            payload = swipePayload,
                                            onParsedChange = onParsedChange,
                                            dragHandleModifier = dragModifier,
                                        )
                                    }

                                    SimpleScriptVerb.DELAY -> {
                                        SimpleDelayForm(
                                            index = index,
                                            parsed = parsed,
                                            onParsedChange = onParsedChange,
                                            dragHandleModifier = dragModifier,
                                        )
                                    }

                                    SimpleScriptVerb.KEY -> {
                                        SimpleKeyForm(
                                            index = index,
                                            parsed = parsed,
                                            onParsedChange = onParsedChange,
                                            dragHandleModifier = dragModifier,
                                        )
                                    }

                                    SimpleScriptVerb.TEXT -> {
                                        SimpleTextForm(
                                            index = index,
                                            parsed = parsed,
                                            onParsedChange = onParsedChange,
                                            dragHandleModifier = dragModifier,
                                        )
                                    }

                                    SimpleScriptVerb.SET_DISPLAY -> {
                                        SimpleSetDisplayForm(
                                            index = index,
                                            parsed = parsed,
                                            onParsedChange = onParsedChange,
                                            dragHandleModifier = dragModifier,
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    color = simpleScriptUiColors().divider,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        } else {
                            val hint = parsedResult.exceptionOrNull()?.message ?: "parse error"
                            SimpleScriptRawLine(
                                index = index,
                                rawLine = line,
                                dragHandleModifier = dragModifier,
                                errorHint = hint,
                                onRawLineChange = {
                                    lines[index] = it
                                    pushSteps()
                                },
                                onDelete = {
                                    lines.removeAt(index)
                                    pushSteps()
                                },
                            )
                        }
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        TextButton(
                            onClick = {
                                lines.add(SIMPLE_SCRIPT_DEFAULT_STEP_LINE)
                                pushSteps()
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
            } else {
                item {
                    Section(name = "Lua 內容") {
                        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                            NoPaddingOutlinedTextField(
                                value = config.init.orEmpty(),
                                onValueChange = { v ->
                                    onUpdateConfig { it.copy(init = v.ifBlank { null }) }
                                },
                                label = "Init（Lua，可選）",
                                singleLine = false,
                            )
                            NoPaddingOutlinedTextField(
                                value = config.code,
                                onValueChange = { v ->
                                    onUpdateConfig { it.copy(code = v) }
                                },
                                label = "Lua",
                                singleLine = false,
                            )
                            NoPaddingOutlinedTextField(
                                value = config.clean.orEmpty(),
                                onValueChange = { v ->
                                    onUpdateConfig { it.copy(clean = v.ifBlank { null }) }
                                },
                                label = "Clean（Lua，可選）",
                                singleLine = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
