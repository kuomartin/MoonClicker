package com.xaxaxax.relc.simplescript.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.simplescript.domain.model.Script

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptListScreen(
    scripts: List<Script>,
    onAddScript: () -> Unit,
    onScriptClick: (Script) -> Unit,
    onDeleteScript: (Script) -> Unit,
    onRunScript: (Script) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("SimpleScript V2") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddScript) {
                Icon(Icons.Default.Add, contentDescription = "Add Script")
            }
        }
    ) { paddingValues ->
        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("No scripts found. Create one!")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scripts) { script ->
                    ScriptListItem(
                        script = script,
                        onClick = { onScriptClick(script) },
                        onDelete = { onDeleteScript(script) },
                        onRun = { onRunScript(script) }
                    )
                }
            }
        }
    }
}

@Composable
fun ScriptListItem(
    script: Script,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name.ifEmpty { "Unnamed Script" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${script.events.size} Events | ${script.fps} FPS",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRun) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScriptListScreenPreview() {
    MaterialTheme {
        ScriptListScreen(
            scripts = listOf(
                Script(name = "Auto Farm", fps = 30),
                Script(name = "Auto Login", fps = 15)
            ),
            onAddScript = {},
            onScriptClick = {},
            onDeleteScript = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ScriptListScreenEmptyPreview() {
    MaterialTheme {
        ScriptListScreen(
            scripts = emptyList(),
            onAddScript = {},
            onScriptClick = {},
            onDeleteScript = {}
        )
    }
}
