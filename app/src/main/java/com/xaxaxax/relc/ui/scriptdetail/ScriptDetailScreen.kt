package com.xaxaxax.relc.ui.scriptdetail

import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.overlay.startClickAssistOverlay
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptConfig.ScriptCodeType
import com.xaxaxax.relc.script.ScriptState
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SimpleScriptBodyJson
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import com.xaxaxax.relc.script.simple.encodeToLine
import com.xaxaxax.relc.script.simple.parseSimpleScriptLine
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
    val ctx = LocalContext.current

    val overlayLaunch: (() -> Unit)? =
        config?.let { sc ->
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !Settings.canDrawOverlays(ctx)
                ) {
                    Toast.makeText(
                        ctx,
                        "請先開啟「在其他應用程式上疊加顯示」權限",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    viewModel.saveScript()
                    startClickAssistOverlay(ctx, sc.id)
                }
            }
        }

    val hasChanges by viewModel.hasChanges.collectAsState()
    var showExitConfirmation by remember { mutableStateOf(false) }

    fun tryNavigateBack() {
        if (hasChanges) {
            showExitConfirmation = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler {
        tryNavigateBack()
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("捨棄變更？") },
            text = { Text("您有尚未儲存的變更，確定要離開嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        onNavigateBack()
                    }
                ) {
                    Text("捨棄")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text("取消")
                }
            }
        )
    }

    ScriptDetailScreenContent(
        config = config,
        state = state,
        latestLog = logs,
        onNavigateBack = { tryNavigateBack() },
        onUpdateConfig = { viewModel.updateConfig(it) },
        onSave = { viewModel.saveScript() },
        onPlay = {
            config?.let { sc ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
                    Toast.makeText(ctx, "需要「疊加顯示」權限以顯示 UI", Toast.LENGTH_SHORT).show()
                } else {
                    startClickAssistOverlay(ctx, sc.id)
                }
                viewModel.scriptManager.startScript(sc)
            }
        },
        onStop = { config?.let { viewModel.scriptManager.stopScript(it.id) } },
        onLaunchFloatingAssist = overlayLaunch,
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
    onStop: () -> Unit,
    onLaunchFloatingAssist: (() -> Unit)? = null,
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
                    if (onLaunchFloatingAssist != null) {
                        TextButton(onClick = { onLaunchFloatingAssist() }) {
                            Text("浮窗")
                        }
                    }
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
    ) {
        Section(name = "基本資訊") {
            FormOutlinedField(
                value = currentConfig.name,
                onValueChange = { name ->
                    onUpdateConfig {
                        when (it) {
                            is ScriptConfig.Lua -> it.copy(name = name)
                            is ScriptConfig.Simple -> it.copy(name = name)
                        }
                    }
                },
                label = "名稱",
            )
            FormOutlinedField(
                value = currentConfig.description,
                onValueChange = { description ->
                    onUpdateConfig {
                        when (it) {
                            is ScriptConfig.Lua -> it.copy(description = description)
                            is ScriptConfig.Simple -> it.copy(description = description)
                        }
                    }
                },
                label = "描述",
            )
        }

        Section(name = "執行策略") {
            ScriptTypeSegmentedButton(currentType = currentConfig.type) { newType ->
                if (newType == currentConfig.type) return@ScriptTypeSegmentedButton
                onUpdateConfig {
                    when (it) {
                        // TODO
                        is ScriptConfig.Lua -> it.copy()
                        is ScriptConfig.Simple -> it.copy()
                    }
                }
            }
            if (currentConfig is ScriptConfig.Simple)
                MacroLoopEditor(
                    loopMode = currentConfig.loopMode,
                    onLoopModeChange = { mode ->
                        onUpdateConfig { (it as? ScriptConfig.Simple)?.copy(loopMode = mode) ?: it }
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
            .padding(bottom = 80.dp) // Space for FAB
    ) {
        Section(name = "內容") {
            when (currentConfig) {
                is ScriptConfig.Lua -> {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        FormOutlinedField(
                            value = currentConfig.code,
                            onValueChange = { code ->
                                onUpdateConfig { it.copyToLua(code = code) }
                            },
                            label = "Lua",
                            minLines = 12,
                            singleLine = false,
                        )
                    }
                }

                is ScriptConfig.Simple -> {
                    SimpleScriptEditor(
                        scriptId = currentConfig.id,
                        code = SimpleScriptCodec.encode(
                            SimpleScriptBodyJson(currentConfig.steps.map { it.encodeToLine() })
                        ),
                        onBodyChanged = { body ->
                            onUpdateConfig { cfg ->
                                cfg.copyToSimple(steps = body.steps.map { parseSimpleScriptLine(it) })
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
                config = ScriptConfig.Lua(
                    id = "1",
                    name = "My Awesome Script",
                    description = "",
                    code = "log('Running script...')\ndisplayId = 0\ninput.tap(50, 500, 500)\nsleep(1000)\nlog('Done!')",
                ),
                state = ScriptState.IDLE,
                latestLog = "[Lua] Running script...",
                onNavigateBack = {},
                onUpdateConfig = {},
                onSave = {},
                onPlay = {},
                onStop = {},
                onLaunchFloatingAssist = null,
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
                currentConfig = ScriptConfig.Simple(
                    id = "1",
                    name = "My Awesome Script",
                    description = "",
                    steps = emptyList()
                ), {}
            )
        }
    }
}
//id: String = this.id,name: String = this.name,description: String = this.description

private fun ScriptConfig.copyToLua(
    id: String = this.id,
    name: String = this.name,
    description: String = this.description,
    code: String? = null
): ScriptConfig.Lua {
    return when (this) {
        is ScriptConfig.Simple -> ScriptConfig.Lua(id, name, description, code ?: "")
        is ScriptConfig.Lua -> this.copy(
            id = id,
            name = name,
            description = description,
            code = code ?: this.code
        )
    }
}

private fun ScriptConfig.copyToSimple(
    id: String = this.id,
    name: String = this.name,
    description: String = this.description,
    steps :List<ParsedSimpleLine>?=null
): ScriptConfig.Simple {
    return when (this) {
        is ScriptConfig.Lua -> ScriptConfig.Simple(id, name, description, steps?:emptyList())
        is ScriptConfig.Simple -> this.copy(
            id = id,
            name = name,
            description = description,
            steps = steps?:this.steps
        )
    }
}
