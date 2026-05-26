package com.xaxaxax.relc.ui.scriptdetail

import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.overlay.OverlayServiceProvider
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptState
import com.xaxaxax.relc.ui.component.Section
import com.xaxaxax.relc.ui.theme.ReLCTheme

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
                    val success = OverlayServiceProvider.showOverlay(sc.id)
                    if (!success) {
                        android.widget.Toast.makeText(ctx, "請先在設定中開啟無障礙服務", android.widget.Toast.LENGTH_SHORT).show()
                    }
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
        onUpdateConfig = { newConfig -> viewModel.updateConfig { newConfig } },
        onSave = { viewModel.saveScript() },
        onPlay = {
            config?.let { sc ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
                    Toast.makeText(ctx, "需要「疊加顯示」權限以顯示 UI", Toast.LENGTH_SHORT).show()
                } else {
                    val success = OverlayServiceProvider.showOverlay(sc.id)
                    if (!success) {
                        android.widget.Toast.makeText(ctx, "請先在設定中開啟無障礙服務", android.widget.Toast.LENGTH_SHORT).show()
                    }
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
    onUpdateConfig: (ScriptConfig) -> Unit,
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
                Column(modifier = Modifier.weight(1f)) {
                    when (currentConfig) {
                        is ScriptConfig.Lua -> LuaScriptEditorLayout(currentConfig, onUpdateConfig)
                        is ScriptConfig.Simple -> SimpleScriptEditorLayout(
                            currentConfig,
                            onUpdateConfig
                        )
                    }
                }
                ScriptConsole(logLines = logLines)
            }
        }
    }
}

@Composable
private fun LuaScriptEditorLayout(
    currentConfig: ScriptConfig.Lua,
    onUpdateConfig: (ScriptConfig) -> Unit,
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
                onValueChange = { name ->
                    val newConfig = currentConfig.copy(name = name)
                    onUpdateConfig(newConfig)
                },
                label = "名稱",
            )
            FormOutlinedField(
                value = currentConfig.description,
                onValueChange = { description ->
                    val newConfig = currentConfig.copy(description = description)
                    onUpdateConfig(newConfig)
                },
                label = "描述",
            )
        }
        Section(name = "內容") {
            FormOutlinedField(
                value = currentConfig.code,
                onValueChange = { code ->
                    val newConfig = currentConfig.copy(code = code)
                    onUpdateConfig(newConfig)
                },
                label = "Lua",
                minLines = 12,
                singleLine = false,
            )
        }
    }
}

@Composable
private fun SimpleScriptEditorLayout(
    currentConfig: ScriptConfig.Simple,
    onUpdateConfig: (ScriptConfig.Simple) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp) // Space for FAB
    ) {
        Section(name = "基本資訊") {
            FormOutlinedField(
                value = currentConfig.name,
                onValueChange = { name ->
                    val newConfig = currentConfig.copy(name = name)
                    onUpdateConfig(newConfig)
                },
                label = "名稱",
            )
            FormOutlinedField(
                value = currentConfig.description,
                onValueChange = { description ->
                    val newConfig = currentConfig.copy(description = description)
                    onUpdateConfig(newConfig)
                },
                label = "描述",
            )
        }
        Section(name = "執行策略") {
            MacroLoopEditor(
                loopMode = currentConfig.loopMode,
                onLoopModeChange = { mode ->
                    val newConfig = currentConfig.copy(loopMode = mode)
                    onUpdateConfig(newConfig)
                },
            )
        }
        Section(name = "內容", modifier = Modifier.weight(1f)) {
            SimpleScriptEditor(
                scriptId = currentConfig.id,
                steps = currentConfig.steps,
                onStepsChange = { steps ->
                    val newConfig = currentConfig.copy(steps = steps)
                    onUpdateConfig(newConfig)
                },
            )
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
fun SimpleEditorPreview() {
    ReLCTheme {
        Surface {
            SimpleScriptEditorLayout(
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
