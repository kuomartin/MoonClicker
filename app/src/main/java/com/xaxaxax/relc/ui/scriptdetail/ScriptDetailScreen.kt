package com.xaxaxax.relc.ui.scriptdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xaxaxax.relc.script.ScriptState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreen(
    id: String,
    onNavigateBack: () -> Unit,
    viewModel: ScriptDetailViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    val state by viewModel.scriptManager.state.collectAsState()
    val logs by viewModel.scriptManager.logs.collectAsState(initial = "")

    // Maintain a simple list of recent logs
    val logList = remember { mutableStateListOf<String>() }
    LaunchedEffect(logs) {
        if (logs.isNotEmpty()) {
            logList.add(logs)
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
                        IconButton(onClick = { viewModel.scriptManager.stopScript() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = {
                            config?.let {
                                viewModel.saveScript()
                                viewModel.scriptManager.startScript(it)
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Button(onClick = {
                        viewModel.saveScript()
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
                    onValueChange = { newValue -> viewModel.updateConfig { it.copy(name = newValue) } },
                    label = { Text("Script Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = currentConfig.code,
                    onValueChange = { viewModel.updateConfig { c -> c.copy(code = it) } },
                    label = { Text("Lua Code") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Console Area
                Text("Console", style = MaterialTheme.typography.titleSmall)
                Surface(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
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
