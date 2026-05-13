package com.xaxaxax.relc.ui.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: ScriptsViewModel = hiltViewModel()
) {
    val scripts by viewModel.scripts.collectAsState()
    val scriptState by viewModel.scriptState.collectAsState()

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
            item {
                Text(
                    text = "Status: ${scriptState.name}",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(scripts) { script ->
                ScriptItem(
                    script = script,
                    onClick = { onNavigateToDetail(script.id) },
                    onPlay = { viewModel.startScript(script) },
                    onStop = { viewModel.stopScript() },
                    isRunning = scriptState == ScriptState.RUNNING
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
                    text = "Mode: ${script.loopMode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isRunning) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
