package com.xaxaxax.relc.ui.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.xaxaxax.relc.ui.component.shizukuStatusAppearance
import com.xaxaxax.relc.ui.theme.ReLCTheme
import com.xaxaxax.relc.workbench.QrCodeGenerator

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
    // targetSdk 37（Android 17）起，接受區網的 inbound TCP 連線需要這個 runtime permission，
    // 不然 VS Code 端連得上 TCP 卻永遠讀不到回應。拒絕也讓開關照常打開——本機診斷用途
    // 還是能動，只是外部連不進來，不因為這個權限擋住整個功能。
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}

    SettingsScreenContent(
        uiState = uiState,
        onOpenShizuku = { launcher.launch(viewModel.getOpenShizukuIntent()) },
        onRequestShizukuPermission = { viewModel.requestShizukuPermission() },
        onRefresh = { viewModel.refreshPermissions(true) },
        onAutoOpenFullscreenChange = viewModel::setAutoOpenFullscreen,
        onAutoStartUserServiceChange = viewModel::setAutoStartUserService,
        onWorkbenchEnabledChange = { enabled ->
            if (enabled) {
                localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            }
            viewModel.setWorkbenchEnabled(enabled)
        },
        onStartUserService = viewModel::startUserService,
        onStopUserService = viewModel::stopUserService,
        onRestartUserService = viewModel::restartUserService,
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
    onAutoStartUserServiceChange: (Boolean) -> Unit = {},
    onWorkbenchEnabledChange: (Boolean) -> Unit = {},
    onStartUserService: () -> Unit = {},
    onStopUserService: () -> Unit = {},
    onRestartUserService: () -> Unit = {},
) {
    // 關閉與重啟都會連帶銷毀虛擬顯示，值得先問一句。
    var pendingAction by remember { mutableStateOf<UserServiceAction?>(null) }
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
                                title = "Shizuku Authorized",
                                description = "Permission granted."
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
                        ToggleSettingItem(
                            name = stringResource(R.string.settings_workbench),
                            description = stringResource(R.string.settings_workbench_note),
                            checked = uiState.workbenchEnabled,
                            onCheckedChange = onWorkbenchEnabledChange,
                        )
                        if (uiState.workbenchEnabled) {
                            WorkbenchQrCode(address = uiState.workbenchAddress)
                        }
                    }
                }

                item {
                    Section(name = stringResource(R.string.settings_user_service)) {
                        UserServiceStatusRow(uiState.shizukuStatus)
                        ToggleSettingItem(
                            name = stringResource(R.string.settings_auto_start_user_service),
                            description = stringResource(R.string.settings_auto_start_user_service_note),
                            checked = uiState.autoStartUserService,
                            onCheckedChange = onAutoStartUserServiceChange,
                        )
                        UserServiceActions(
                            uiState = uiState,
                            onStart = onStartUserService,
                            onRequestStop = { pendingAction = UserServiceAction.STOP },
                            onRequestRestart = { pendingAction = UserServiceAction.RESTART },
                        )
                    }
                }
            }
        }
    }

    pendingAction?.let { action ->
        UserServiceConfirmDialog(
            action = action,
            onConfirm = {
                pendingAction = null
                when (action) {
                    UserServiceAction.STOP -> onStopUserService()
                    UserServiceAction.RESTART -> onRestartUserService()
                }
            },
            onDismiss = { pendingAction = null },
        )
    }
}

/** 需要先跟使用者確認的兩個動作——兩者都會銷毀虛擬顯示。 */
private enum class UserServiceAction { STOP, RESTART }

@Composable
private fun UserServiceConfirmDialog(
    action: UserServiceAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message) = when (action) {
        UserServiceAction.STOP ->
            R.string.settings_user_service_stop_title to
                    R.string.settings_user_service_stop_message
        UserServiceAction.RESTART ->
            R.string.settings_user_service_restart_title to
                    R.string.settings_user_service_restart_message
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_user_service_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_user_service_cancel))
            }
        },
    )
}

/** 這個區塊在講的那個服務現在是什麼狀態。詞彙與狀態列共用，兩邊不會各說各話。 */
@Composable
private fun UserServiceStatusRow(status: ShizukuConnectionStatus) {
    val (label, tint) = shizukuStatusAppearance(status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 固定寬度的槽位，圓點與轉圈換手時文字才不會跟著左右跳。
        Box(
            modifier = Modifier.size(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (status == ShizukuConnectionStatus.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = tint,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(tint, CircleShape)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
private fun UserServiceActions(
    uiState: SettingsUiState,
    onStart: () -> Unit,
    onRequestStop: () -> Unit,
    onRequestRestart: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onStart,
                enabled = uiState.canStartUserService,
            ) {
                Text(stringResource(R.string.settings_user_service_start))
            }
            OutlinedButton(
                onClick = onRequestRestart,
                enabled = uiState.canStopUserService,
            ) {
                Text(stringResource(R.string.settings_user_service_restart))
            }
            OutlinedButton(
                onClick = onRequestStop,
                enabled = uiState.canStopUserService,
            ) {
                Text(stringResource(R.string.settings_user_service_stop))
            }
        }
        if (uiState.isScriptRunning) {
            Text(
                text = stringResource(R.string.settings_user_service_script_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
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

/**
 * Server 開啟後才顯示（見呼叫端），[address] 是 server 目前 bind 到的位址；bind 還在進行中
 * （或者剛好卡在失敗邊緣）會是 null，此時顯示文字提示而不是空白或過期的 QR code。
 */
@Composable
private fun WorkbenchQrCode(address: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        if (address != null) {
            val qrBitmap = remember(address) {
                QrCodeGenerator.toBitmap(QrCodeGenerator.encode(address)).asImageBitmap()
            }
            Image(
                bitmap = qrBitmap,
                contentDescription = stringResource(R.string.settings_workbench_qr_description),
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.settings_workbench_qr_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
