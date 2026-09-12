package com.xaxaxax.relc.ui.scripts

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentCompositionContext
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.R
import com.xaxaxax.relc.toastNotImplement
import com.xaxaxax.relc.ui.theme.ReLCTheme

@Composable
fun ScriptsScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: ScriptsViewModel = hiltViewModel()
) {

    ScriptsScreenContent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreenContent() {
    val context = LocalContext.current
    Scaffold(
        floatingActionButton = {
            var expanded by remember { mutableStateOf(false) }
            Box {
                FloatingActionButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Script")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("新增 Lua 腳本") },
                        onClick = {
                            expanded = false
                            toastNotImplement(context)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("新增簡易腳本") },
                        onClick = {
                            expanded = false
                            toastNotImplement(context)
                        }
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize()
        ) {
            item{
                ScriptItem(
                    onPlay = {},
                    onStop = {},
                    onClick = {},
                    isRunning = false
                )
            }
        }
    }
}

@Composable
fun ScriptItem(
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean
) {
    // TODO
    val name = "Placeholder"
    val description = "description"
    val typeName = "type"

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
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                if (description.isNotEmpty()) {
                    Text(text = description, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = typeName,
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

/**
 * Marks a script as currently executing. With the overlay window gone this list and the
 * status notification are the only places a run is visible.
 */
@Composable
private fun RunningBadge() {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.script_running_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ScriptsScreenPreview() {
    ReLCTheme {
        ScriptsScreenContent(
        )
    }
}
