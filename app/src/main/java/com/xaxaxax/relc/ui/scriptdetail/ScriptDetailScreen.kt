package com.xaxaxax.relc.ui.scriptdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptState

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
    // Maintain a simple list of recent logs
    val logList = remember { mutableStateListOf<String>() }
    LaunchedEffect(latestLog) {
        if (latestLog.isNotEmpty()) {
            logList.add(latestLog)
            if (logList.size > 50) logList.removeAt(0)
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
                    if (state == ScriptState.RUNNING) {
                        IconButton(onClick = onStop) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            onSave()
                            onPlay()
                        }) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Button(onClick = {
                        onSave()
                        onNavigateBack()
                    }) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        config?.let { currentConfig ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = currentConfig.name,
                    onValueChange = { newValue -> onUpdateConfig { it.copy(name = newValue) } },
                    label = { Text("Script Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = currentConfig.code,
                    onValueChange = { newValue -> onUpdateConfig { c -> c.copy(code = newValue) } },
                    label = { Text("Lua Code") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Console Area
                Text("Console", style = MaterialTheme.typography.titleSmall)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        logList.takeLast(10).forEach { log ->
                            Text(text = log, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScriptDetailScreenPreview() {
    com.xaxaxax.relc.ui.theme.ReLCTheme {
        Surface {
            ScriptDetailScreenContent(
                config = ScriptConfig(
                    id = "1",
                    name = "My Awesome Script",
                    description = "",
                    code = "log('Running script...')\ninput.tap(500, 500, 0)\nsleep(1000)\nlog('Done!')"
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
