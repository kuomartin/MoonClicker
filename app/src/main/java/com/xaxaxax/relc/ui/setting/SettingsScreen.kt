package com.xaxaxax.relc.ui.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.R
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.ui.component.Section
import com.xaxaxax.relc.ui.theme.ReLCTheme

typealias HealthCheckActions = List<Pair<String, () -> Unit>>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }

    SettingsScreenContent(
        uiState = uiState,
        onOpenShizuku = { launcher.launch(viewModel.getOpenShizukuIntent()) },
        onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
        onRefresh = { viewModel.refreshPermissions(true) },
        onAutoOpenFullscreenChange = viewModel::setAutoOpenFullscreen,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsScreenContent(
    uiState: SettingsUiState,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefresh: () -> Unit,
    onAutoOpenFullscreenChange: (Boolean) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        modifier = Modifier,
        topBar = {
            MediumTopAppBar(
                title = { Text("Settings") },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            state = pullRefreshState,
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullRefreshState,
                    isRefreshing = uiState.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                item {
                    Section("Health Check") {
                        val hasAnyFailure = !uiState.shizukuStatus.isAuthorized ||
                                !uiState.osAllowSecondaryDisplays

                        if (hasAnyFailure) {
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                shape = MaterialTheme.shapes.extraLarge
                            ) {
                                Column {
                                    var first = true
                                    if (uiState.shizukuStatus == ShizukuConnectionStatus.NOT_AVAILABLE) {
                                        HealthCheckFailedItem(
                                            painterResource(R.drawable.ic_shizuku_icon),
                                            "Shizuku Not Running",
                                            "Start the Shizuku service to enable advanced features.",
                                            actions = listOf("Launch Shizuku" to onOpenShizuku)
                                        )
                                        first = false
                                    } else if (uiState.shizukuStatus == ShizukuConnectionStatus.NEED_PERMISSION) {
                                        HealthCheckFailedItem(
                                            painterResource(R.drawable.ic_shizuku_icon),
                                            "Shizuku Permission Required",
                                            "Permission is required to interact with system services.",
                                            actions = listOf("Grant Permission" to onRequestShizukuPermission)
                                        )
                                        first = false
                                    }

                                    if (!uiState.osAllowSecondaryDisplays) {
                                        if (!first) HorizontalDivider(
                                            modifier = Modifier.padding(
                                                horizontal = 16.dp
                                            )
                                        )
                                        HealthCheckFailedItem(
                                            painterResource(R.drawable.ic_picture_in_picture_off),
                                            "Secondary Displays Disabled",
                                            "The system disabled secondary displays, which is an important feature for this app.",
                                            actions = listOf()
                                        )
                                    }
                                }
                            }
                        }
                        if (uiState.shizukuStatus.isAuthorized) {
                            HealthCheckGood(
                                painter = painterResource(R.drawable.ic_shizuku_icon),
                                title = "Shizuku Running",
                                description = "Service is active and authorized."
                            )
                        }
                    }
                }

                item {
                    Section(name = stringResource(R.string.settings_general)) {
                        ToggleSettingItem(
                            name = stringResource(R.string.settings_auto_fullscreen),
                            description = stringResource(R.string.settings_auto_fullscreen_note),
                            checked = uiState.autoOpenFullscreen,
                            onCheckedChange = onAutoOpenFullscreenChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingItem(
    name: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HealthCheckGood(
    painter: Painter, title: String, description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color(0xFF4CAF50)
        )
    }
}

@Composable
private fun HealthCheckFailedItem(
    painter: Painter, title: String, description: String, actions: HealthCheckActions
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.padding(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actions.isNotEmpty()) {
            Spacer(modifier = Modifier.padding(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (name, action) ->
                    Button(
                        onClick = action,
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(text = name)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    ReLCTheme {
        Surface {
            var uiState by remember { mutableStateOf(SettingsUiState()) }
            SettingsScreenContent(
                uiState = uiState,
                onOpenShizuku = {
                    uiState = uiState.copy(
                        shizukuStatus = ShizukuConnectionStatus.NEED_PERMISSION
                    )
                },
                onRequestShizukuPermission = {
                    uiState = uiState.copy(
                        shizukuStatus = ShizukuConnectionStatus.CONNECTED
                    )
                },
                onRefresh = { uiState = SettingsUiState() },
                onAutoOpenFullscreenChange = {
                    uiState = uiState.copy(autoOpenFullscreen = it)
                },
            )
        }
    }
}
