package com.xaxaxax.relc.ui.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.script.LoopMode
import com.xaxaxax.relc.script.ScriptCodeType
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptState
import kotlin.time.Duration.Companion.seconds

@Composable
fun ScriptsScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: ScriptsViewModel = hiltViewModel()
) {
    val scripts by viewModel.scripts.collectAsState()
    val scriptStates by viewModel.scriptStates.collectAsState()

    ScriptsScreenContent(
        scripts = scripts,
        scriptStates = scriptStates,
        onNavigateToDetail = onNavigateToDetail,
        onPlay = { viewModel.startScript(it) },
        onStop = { viewModel.stopScript(it.id) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreenContent(
    scripts: List<ScriptConfig>,
    scriptStates: Map<String, ScriptState>,
    onNavigateToDetail: (String) -> Unit,
    onPlay: (ScriptConfig) -> Unit,
    onStop: (ScriptConfig) -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToDetail("new") }) {
                Icon(Icons.Default.Add, contentDescription = "Add Script")
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize()
        ) {
            items(scripts) { script ->
                val state = scriptStates[script.id] ?: ScriptState.IDLE
                ScriptItem(
                    script = script,
                    onClick = { onNavigateToDetail(script.id) },
                    onPlay = { onPlay(script) },
                    onStop = { onStop(script) },
                    isRunning = state == ScriptState.RUNNING
                )
            }
        }
    }
}

@Composable
fun ScriptItem(
    script: ScriptConfig,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = script.name, style = MaterialTheme.typography.titleMedium)
                if (script.description.isNotEmpty()) {
                    Text(text = script.description, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "${script.type.name} · ${script.loopMode.string}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isRunning) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                IconButton(onClick = onPlay) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScriptsScreenPreview() {
    com.xaxaxax.relc.ui.theme.ReLCTheme {
        ScriptsScreenContent(
            scripts = listOf(
                ScriptConfig(
                    id = "1",
                    name = "Test Script 1",
                    description = "This is a test script.",
                    type = ScriptCodeType.LUA,
                    init = null,
                    code = "print('hello')",
                    clean = null,
                    alwaysRunClean = false,
                    loopMode = LoopMode.None,
                ),
                ScriptConfig(
                    id = "2",
                    name = "Test Script 2",
                    description = "Another test script.",
                    type = ScriptCodeType.LUA,
                    init = null,
                    code = "print('world')",
                    clean = null,
                    alwaysRunClean = false,
                    loopMode = LoopMode.Inf(1.seconds),
                )
            ),
            scriptStates = mapOf("1" to ScriptState.RUNNING),
            onNavigateToDetail = {},
            onPlay = {},
            onStop = {}
        )
    }
}
