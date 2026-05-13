package com.xaxaxax.relc.ui.scriptdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xaxaxax.relc.script.LoopMode
import com.xaxaxax.relc.script.ScriptCodeType
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptState
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import com.xaxaxax.relc.ui.component.Section
import com.xaxaxax.relc.ui.scriptdetail.component.ScriptTypeSegmentedButton
import com.xaxaxax.relc.ui.theme.ReLCTheme
import kotlinx.coroutines.launch

@Composable
fun ScriptDetailScreen(
    id: String,
    onNavigateBack: () -> Unit,
    viewModel: ScriptDetailViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    val state by viewModel.scriptState.collectAsState()
    val logs by viewModel.logs.collectAsState(initial = "")

    ScriptDetailScreenContent(
        config = config,
        state = state,
        latestLog = logs,
        onNavigateBack = onNavigateBack,
        onUpdateConfig = { viewModel.updateConfig(it) },
        onSave = { viewModel.saveScript() },
        onPlay = { config?.let { viewModel.scriptManager.startScript(it) } },
        onStop = { config?.let { viewModel.scriptManager.stopScript(it.id) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreenContent(
    config: ScriptConfig?,
    state: ScriptState,
    latestLog: String,
    onNavigateBack: () -> Unit,
    onUpdateConfig: ((ScriptConfig) -> ScriptConfig) -> Unit,
    onSave: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit
) {
    val logLines = remember { mutableStateListOf<String>() }
    LaunchedEffect(latestLog) {
        if (latestLog.isNotEmpty()) {
            logLines.add(latestLog)
            if (logLines.size > 50) logLines.removeAt(0)
        }
    }

    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config?.name ?: "Loading...") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onSave()
                    }) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (config != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (state == ScriptState.RUNNING) {
                            onStop()
                        } else {
                            onSave()
                            onPlay()
                        }
                    },
                    icon = {
                        Icon(
                            if (state == ScriptState.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(if (state == ScriptState.RUNNING) "停止" else "執行")
                    },
                    containerColor = if (state == ScriptState.RUNNING) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (state == ScriptState.RUNNING) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { padding ->
        config?.let { currentConfig ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text("配置") }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text("編輯器") }
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Top
                ) { page ->
                    when (page) {
                        0 -> ConfigPage(currentConfig, onUpdateConfig)
                        1 -> EditorPage(currentConfig, onUpdateConfig)
                    }
                }

                ScriptConsole(logLines = logLines)
            }
        }
    }
}

@Composable
private fun ConfigPage(
    currentConfig: ScriptConfig,
    onUpdateConfig: ((ScriptConfig) -> ScriptConfig) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 80.dp) // Space for FAB
    ) {
        Section(name = "基本資訊") {
            FormOutlinedField(
                value = currentConfig.name,
                onValueChange = { v -> onUpdateConfig { it.copy(name = v) } },
                label = "名稱",
            )
            FormOutlinedField(
                value = currentConfig.description,
                onValueChange = { v -> onUpdateConfig { it.copy(description = v) } },
                label = "描述",
            )
        }

        Section(name = "執行策略") {
            ScriptTypeSegmentedButton(currentType = currentConfig.type){ newType ->
                if (newType == currentConfig.type) return@ScriptTypeSegmentedButton
                onUpdateConfig { cfg ->
                    when (newType) {
                        ScriptCodeType.LUA -> cfg.copy(
                            type = ScriptCodeType.LUA,
                            code = cfg.code.ifBlank { "log(\"hello\")\n" },
                        )

                        ScriptCodeType.SIMPLE -> cfg.copy(
                            type = ScriptCodeType.SIMPLE,
                            code = SimpleScriptCodec.emptyBodyJson(),
                        )
                    }
                }
            }
            MacroLoopEditor(
                loopMode = currentConfig.loopMode,
                onLoopModeChange = { mode ->
                    onUpdateConfig { it.copy(loopMode = mode) }
                },
            )
        }
    }
}

@Composable
private fun EditorPage(
    currentConfig: ScriptConfig,
    onUpdateConfig: ((ScriptConfig) -> ScriptConfig) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 80.dp) // Space for FAB
    ) {
        Section(name = "內容") {
            when (currentConfig.type) {
                ScriptCodeType.LUA -> {
                    FormOutlinedField(
                        value = currentConfig.init.orEmpty(),
                        onValueChange = { v ->
                            onUpdateConfig { it.copy(init = v.ifBlank { null }) }
                        },
                        label = "Init（Lua，可選）",
                        minLines = 2,
                        singleLine = false,
                    )
                    FormOutlinedField(
                        value = currentConfig.code,
                        onValueChange = { v ->
                            onUpdateConfig { it.copy(code = v) }
                        },
                        label = "Lua",
                        minLines = 12,
                        singleLine = false,
                    )
                    FormOutlinedField(
                        value = currentConfig.clean.orEmpty(),
                        onValueChange = { v ->
                            onUpdateConfig { it.copy(clean = v.ifBlank { null }) }
                        },
                        label = "Clean（Lua，可選）",
                        minLines = 2,
                        singleLine = false,
                    )
                    AlwaysRunCleanRow(
                        checked = currentConfig.alwaysRunClean,
                        onCheckedChange = { checked ->
                            onUpdateConfig { it.copy(alwaysRunClean = checked) }
                        },
                    )
                }

                ScriptCodeType.SIMPLE -> {
                    SimpleScriptEditor(
                        scriptId = currentConfig.id,
                        code = currentConfig.code,
                        onBodyChanged = { body ->
                            onUpdateConfig {
                                it.copy(code = SimpleScriptCodec.encode(body))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScriptDetailScreenPreview() {
    ReLCTheme {
        Surface {
            ScriptDetailScreenContent(
                config = ScriptConfig(
                    id = "1",
                    name = "My Awesome Script",
                    description = "",
                    type = ScriptCodeType.LUA,
                    init = null,
                    code = "log('Running script...')\ndisplayId = 0\ninput.tap(50, 500, 500)\nsleep(1000)\nlog('Done!')",
                    clean = null,
                    alwaysRunClean = false,
                    loopMode = LoopMode.None,
                ),
                state = ScriptState.IDLE,
                latestLog = "[Lua] Running script...",
                onNavigateBack = {},
                onUpdateConfig = {},
                onSave = {},
                onPlay = {},
                onStop = {}
            )
        }
    }
}
@Preview(showBackground = true)
@Composable
fun EditorPagePreview() {
    ReLCTheme {
        Surface {
            EditorPage(
                currentConfig = ScriptConfig(
                    id = "1",
                    name = "My Awesome Script",
                    description = "",
                    type = ScriptCodeType.SIMPLE,
                    init = null,
                    code = SimpleScriptCodec.emptyBodyJson(),
                    clean = null,
                    alwaysRunClean = false,
                    loopMode = LoopMode.None,
                ),{}
            )
        }
    }
}
