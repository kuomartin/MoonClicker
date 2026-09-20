package com.xaxaxax.moonclicker.ui.scriptdetail

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.ui.component.Section
import com.xaxaxax.moonclicker.ui.scripts.TargetPicker
import com.xaxaxax.moonclicker.ui.theme.MonoFontFamily

/**
 * 唯讀的腳本檢視 + 執行入口。
 *
 * 刻意不提供編輯：腳本是外部私有目錄下的一個資料夾，用檔案管理員或電腦改。這一頁要回答的
 * 是「它現在長怎樣、跑起來會發生什麼」，不是「怎麼改它」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScriptDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var showTargetPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::export) }

    val script = uiState.script
    val isThisRunning = uiState.session.isRunning && uiState.session.script?.id == script?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(script?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { script?.let { exportLauncher.launch("${it.id}.zip") } }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.script_detail_export),
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.script_detail_delete),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (script == null) {
            Text(
                text = stringResource(R.string.script_detail_missing_source),
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isThisRunning) {
                        OutlinedButton(onClick = viewModel::stop) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text(stringResource(R.string.script_detail_stop))
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.run() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(stringResource(R.string.script_detail_run))
                        }
                        OutlinedButton(onClick = {
                            viewModel.refreshDisplays()
                            showTargetPicker = true
                        }) {
                            Text(stringResource(R.string.script_detail_run_on))
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.common_status)) {
                    Text(
                        text = statusText(uiState),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (uiState.sharedData.isNotEmpty()) {
                item {
                    Section(stringResource(R.string.script_detail_shared_data)) {
                        Column {
                            uiState.sharedData.forEach { (key, value) ->
                                Text(
                                    text = "$key = $value",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = MonoFontFamily,
                                    )
                            }
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.script_detail_templates)) {
                    if (uiState.templates.isEmpty()) {
                        Text(
                            text = stringResource(R.string.script_detail_no_templates),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.templates.forEach { file ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    AsyncImage(
                                        model = file,
                                        contentDescription = file.name,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Text(
                                        text = file.toRelativeString(script.dir),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = MonoFontFamily,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.script_detail_source)) {
                    Column {
                        Text(
                            text = stringResource(R.string.script_detail_no_editor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(
                                text = uiState.source
                                    ?: stringResource(R.string.script_detail_missing_source),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFontFamily,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTargetPicker) {
        TargetPicker(
            displays = uiState.virtualDisplays,
            newDisplayConfig = viewModel.newDisplayConfig,
            onPick = {
                showTargetPicker = false
                viewModel.run(it)
            },
            onDismiss = { showTargetPicker = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.script_detail_delete)) },
            text = { Text(stringResource(R.string.scripts_delete_confirm_message, script?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (viewModel.delete()) onNavigateBack()
                }) { Text(stringResource(R.string.script_detail_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun statusText(uiState: ScriptDetailUiState): String {
    val isThisScript = uiState.session.script?.id == uiState.script?.id
    if (!isThisScript) return stringResource(R.string.scripts_status_idle)
    val target = uiState.session.displayId?.let {
        if (it == 0) stringResource(R.string.script_target_physical)
        else stringResource(R.string.script_target_virtual, it)
    }.orEmpty()
    return when (val state = uiState.session.runState) {
        is EngineRunState.Idle -> stringResource(R.string.scripts_status_idle)
        is EngineRunState.Starting -> stringResource(R.string.scripts_status_starting, target)
        is EngineRunState.Running -> stringResource(R.string.scripts_status_running, target)
        is EngineRunState.Finished -> stringResource(R.string.scripts_status_finished, target)
        is EngineRunState.Stopped -> stringResource(R.string.scripts_status_stopped, target)
        is EngineRunState.Error -> stringResource(R.string.scripts_status_error, state.message)
    }
}
