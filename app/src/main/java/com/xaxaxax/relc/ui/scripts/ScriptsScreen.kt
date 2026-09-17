package com.xaxaxax.relc.ui.scripts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.R
import com.xaxaxax.relc.engine.state.EngineRunState
import com.xaxaxax.relc.script.Script
import com.xaxaxax.relc.script.ScriptSessionState
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.ui.component.ShizukuStatusBar
import com.xaxaxax.relc.ui.theme.ReLCTheme
import java.io.File

@Composable
fun ScriptsScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: ScriptsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

    // 使用者可能剛從檔案管理員丟了資料夾進來，回到這頁時重掃一次才看得到。
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::import) }

    var showShizukuRationale by remember { mutableStateOf(false) }

    if (showShizukuRationale) {
        com.xaxaxax.relc.ui.component.PermissionRationaleDialog(
            title = androidx.compose.ui.res.stringResource(com.xaxaxax.relc.R.string.permission_shizuku_rationale_title),
            description = androidx.compose.ui.res.stringResource(com.xaxaxax.relc.R.string.permission_shizuku_rationale_desc),
            icon = androidx.compose.ui.res.painterResource(com.xaxaxax.relc.R.drawable.ic_shizuku_icon),
            onConfirm = { viewModel.onShizukuAction() },
            onDismiss = { showShizukuRationale = false },
        )
    }

    ScriptsScreenContent(
        uiState = uiState,
        onShizukuAction = {
            if (uiState.shizukuStatus == ShizukuConnectionStatus.NEED_PERMISSION) {
                showShizukuRationale = true
            } else {
                viewModel.onShizukuAction()
            }
        },
        onRefresh = viewModel::refresh,
        onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        onPlay = { viewModel.run(it) },
        onStop = viewModel::stop,
        onOpen = { onNavigateToDetail(it.id) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreenContent(
    uiState: ScriptsUiState = ScriptsUiState(),
    onShizukuAction: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onImport: () -> Unit = {},
    onPlay: (Script) -> Unit = {},
    onStop: () -> Unit = {},
    onOpen: (Script) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts_title)) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.scripts_refresh),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onImport) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.scripts_import))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ShizukuStatusBar(status = uiState.shizukuStatus, onActionClick = onShizukuAction)

            if (uiState.scripts.isEmpty()) {
                EmptyState(uiState.scriptsPath)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.scripts, key = { it.id }) { script ->
                        ScriptItem(
                            script = script,
                            session = uiState.session,
                            onClick = { onOpen(script) },
                            onPlay = { onPlay(script) },
                            onStop = onStop,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 空狀態不是裝飾：沒有內建編輯器，所以「腳本要放哪裡」就是使用者第一個、也是唯一需要
 * 知道的事，路徑必須直接寫在這裡而且可以複製。
 */
@Composable
private fun EmptyState(scriptsPath: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.size(48.dp))
        Text(
            text = stringResource(R.string.scripts_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.scripts_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = scriptsPath,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        OutlinedButton(onClick = { copyPath(context, scriptsPath) }) {
            Text(stringResource(R.string.scripts_copy_path))
        }
    }
}

private fun copyPath(context: Context, path: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("scripts", path))
    Toast.makeText(context, R.string.scripts_path_copied, Toast.LENGTH_SHORT).show()
}

@Composable
fun ScriptItem(
    script: Script,
    session: ScriptSessionState,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val isThisRunning = session.isRunning && session.script?.id == script.id
    val otherRunning = session.isRunning && !isThisRunning

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = script.name, style = MaterialTheme.typography.titleMedium)
                    if (isThisRunning) {
                        Spacer(modifier = Modifier.width(8.dp))
                        RunningBadge()
                    }
                }
                if (script.description.isNotEmpty()) {
                    Text(
                        text = script.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = targetSummary(script),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isThisRunning && session.script?.id == script.id) {
                    session.runState.summary()?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (isThisRunning) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                IconButton(onClick = onPlay, enabled = !otherRunning) {
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

@Composable
private fun targetSummary(script: Script): String = script.display?.let {
    "${it.width} × ${it.height} · 虛擬顯示"
} ?: stringResource(R.string.script_target_physical)

private fun EngineRunState.summary(): String? = when (this) {
    is EngineRunState.Error -> message
    is EngineRunState.Finished -> "已完成"
    is EngineRunState.Stopped -> "已停止"
    else -> null
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
            uiState = ScriptsUiState(
                scripts = listOf(
                    Script("daily", File("/tmp/daily"), "自動簽到", "每天開 App 點簽到", null),
                ),
                scriptsPath = "/storage/emulated/0/Android/data/com.xaxaxax.relc/files/scripts",
            )
        )
    }
}
