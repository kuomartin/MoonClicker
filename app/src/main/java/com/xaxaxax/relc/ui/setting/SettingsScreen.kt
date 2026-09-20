package com.xaxaxax.relc.ui.setting

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.R
import com.xaxaxax.relc.TopLevelDestination
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.ui.component.DropdownBox
import com.xaxaxax.relc.ui.component.PermissionRationaleDialog
import com.xaxaxax.relc.ui.component.Section
import com.xaxaxax.relc.ui.component.shizukuStatusAppearance
import com.xaxaxax.relc.ui.theme.ReLCTheme
import com.xaxaxax.relc.ui.theme.SuccessColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class RationaleDialogType {
    NOTIFICATION,
    SHIZUKU,
    LOCAL_NETWORK,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    val activityResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }
    // targetSdk 37（Android 17）起，接受區網的 inbound TCP 連線需要這個 runtime permission，
    // 不然 VS Code 端連得上 TCP 卻永遠讀不到回應。拒絕不影響 Workbench 開關本身——本機診斷用途
    // 還是能動，只是外部連不進來，不因為這個權限擋住整個功能。
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    var activeRationale by remember { mutableStateOf<RationaleDialogType?>(null) }

    when (activeRationale) {
        RationaleDialogType.NOTIFICATION -> {
            PermissionRationaleDialog(
                title = stringResource(R.string.permission_notification_rationale_title),
                description = stringResource(R.string.permission_notification_rationale_desc),
                icon = painterResource(R.drawable.ic_launcher_foreground),
                confirmText = stringResource(R.string.permission_action_proceed),
                dismissText = stringResource(R.string.permission_action_cancel),
                shizukuActionLabel = stringResource(R.string.permission_action_via_shizuku)
                    .takeIf { uiState.shizukuStatus == ShizukuConnectionStatus.CONNECTED },
                onShizukuAction = viewModel::grantNotificationPermissionViaShizuku
                    .takeIf { uiState.shizukuStatus == ShizukuConnectionStatus.CONNECTED },
                onConfirm = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        activityResultLauncher.launch(viewModel.getNotificationSettingsIntent())
                    }
                },
                onDismiss = { activeRationale = null },
            )
        }
        RationaleDialogType.SHIZUKU -> {
            PermissionRationaleDialog(
                title = stringResource(R.string.permission_shizuku_rationale_title),
                description = stringResource(R.string.permission_shizuku_rationale_desc),
                icon = painterResource(R.drawable.ic_shizuku_icon),
                confirmText = stringResource(R.string.permission_action_proceed),
                dismissText = stringResource(R.string.permission_action_cancel),
                onConfirm = {
                    if (uiState.shizukuStatus == ShizukuConnectionStatus.NOT_AVAILABLE) {
                        activityResultLauncher.launch(viewModel.getOpenShizukuIntent())
                    } else {
                        viewModel.requestShizukuPermission()
                    }
                },
                onDismiss = { activeRationale = null },
            )
        }
        RationaleDialogType.LOCAL_NETWORK -> {
            PermissionRationaleDialog(
                title = stringResource(R.string.permission_local_network_rationale_title),
                description = stringResource(R.string.permission_local_network_rationale_desc),
                icon = painterResource(R.drawable.ic_launcher_foreground),
                confirmText = stringResource(R.string.permission_action_proceed),
                dismissText = stringResource(R.string.permission_action_cancel),
                onConfirm = {
                    localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
                },
                onDismiss = { activeRationale = null },
            )
        }
        null -> {}
    }

    SettingsScreenContent(
        uiState = uiState,
        onRequestShizukuPermission = {
            if (uiState.shizukuStatus == ShizukuConnectionStatus.NOT_AVAILABLE ||
                uiState.shizukuStatus == ShizukuConnectionStatus.NEED_PERMISSION
            ) {
                activeRationale = RationaleDialogType.SHIZUKU
            } else {
                viewModel.requestShizukuPermission()
            }
        },
        onRequestNotificationPermission = {
            if (uiState.hasNotificationPermission) {
                activityResultLauncher.launch(viewModel.getNotificationSettingsIntent())
            } else {
                activeRationale = RationaleDialogType.NOTIFICATION
            }
        },
        onRequestLocalNetworkPermission = {
            if (!uiState.hasLocalNetworkPermission) {
                activeRationale = RationaleDialogType.LOCAL_NETWORK
            }
        },
        onRefresh = { viewModel.refreshPermissions(true) },
        onAutoOpenFullscreenChange = viewModel::setAutoOpenFullscreen,
        onDefaultStartPageChange = viewModel::setDefaultStartPage,
        onAppLanguageChange = viewModel::setAppLanguage,
        onAutoStartUserServiceChange = viewModel::setAutoStartUserService,
        onWorkbenchEnabledChange = viewModel::setWorkbenchEnabled,
        onStartUserService = viewModel::startUserService,
        onStopUserService = viewModel::stopUserService,
        onRestartUserService = viewModel::restartUserService,
        onStartPairingMode = viewModel::startPairingMode,
        onStopPairingMode = viewModel::stopPairingMode,
        onSetBruteForceProtection = viewModel::setBruteForceProtectionEnabled,
        onRevokeAllTokens = viewModel::revokeAllTokens,
        onNavigateToAbout = onNavigateToAbout,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsScreenContent(
    uiState: SettingsUiState,
    onRequestShizukuPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestLocalNetworkPermission: () -> Unit = {},
    onRefresh: () -> Unit,
    onAutoOpenFullscreenChange: (Boolean) -> Unit,
    onDefaultStartPageChange: (TopLevelDestination) -> Unit = {},
    onAppLanguageChange: (String) -> Unit = {},
    onAutoStartUserServiceChange: (Boolean) -> Unit = {},
    onWorkbenchEnabledChange: (Boolean) -> Unit = {},
    onStartUserService: () -> Unit = {},
    onStopUserService: () -> Unit = {},
    onRestartUserService: () -> Unit = {},
    onStartPairingMode: () -> Unit = {},
    onStopPairingMode: () -> Unit = {},
    onSetBruteForceProtection: (Boolean) -> Unit = {},
    onRevokeAllTokens: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
) {
    // 關閉與重啟都會連帶銷毀虛擬顯示，值得先問一句。
    var pendingAction by remember { mutableStateOf<UserServiceAction?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        modifier = Modifier,
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
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
                PullToRefreshDefaults.Indicator(
                    state = pullRefreshState,
                    isRefreshing = uiState.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                item {
                    Section(name = stringResource(R.string.settings_permissions_title)) {
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // 1. Shizuku
                                val (shizukuLabel, shizukuTint) = shizukuStatusAppearance(uiState.shizukuStatus)
                                PermissionRow(
                                    painter = painterResource(R.drawable.ic_shizuku_icon),
                                    title = stringResource(R.string.permission_shizuku_title),
                                    description = stringResource(R.string.permission_shizuku_desc),
                                    statusText = shizukuLabel,
                                    statusColor = shizukuTint,
                                    isGranted = uiState.shizukuStatus.isAuthorized,
                                    actionLabel = when (uiState.shizukuStatus) {
                                        ShizukuConnectionStatus.NOT_AVAILABLE -> stringResource(R.string.permission_action_open_shizuku)
                                        ShizukuConnectionStatus.NEED_PERMISSION -> stringResource(R.string.permission_action_grant)
                                        else -> null
                                    },
                                    onAction = onRequestShizukuPermission,
                                    warningText = if (uiState.isShizukuUidWarning) {
                                        stringResource(R.string.permission_shizuku_uid_warning)
                                    } else {
                                        null
                                    },
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                // 2. Notification
                                PermissionRow(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    title = stringResource(R.string.permission_notification_title),
                                    description = stringResource(R.string.permission_notification_desc),
                                    statusText = if (uiState.hasNotificationPermission) {
                                        stringResource(R.string.permission_granted)
                                    } else {
                                        stringResource(R.string.permission_not_granted)
                                    },
                                    statusColor = if (uiState.hasNotificationPermission) SuccessColor else MaterialTheme.colorScheme.error,
                                    isGranted = uiState.hasNotificationPermission,
                                    actionLabel = if (uiState.hasNotificationPermission) {
                                        stringResource(R.string.permission_action_settings)
                                    } else {
                                        stringResource(R.string.permission_action_grant)
                                    },
                                    onAction = onRequestNotificationPermission,
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                // 3. Local Network
                                PermissionRow(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    title = stringResource(R.string.permission_local_network_title),
                                    description = stringResource(R.string.permission_local_network_desc),
                                    statusText = if (uiState.hasLocalNetworkPermission) {
                                        stringResource(R.string.permission_granted)
                                    } else {
                                        stringResource(R.string.permission_not_granted)
                                    },
                                    statusColor = if (uiState.hasLocalNetworkPermission) SuccessColor else MaterialTheme.colorScheme.error,
                                    isGranted = uiState.hasLocalNetworkPermission,
                                    actionLabel = if (uiState.hasLocalNetworkPermission) {
                                        null
                                    } else {
                                        stringResource(R.string.permission_action_grant)
                                    },
                                    onAction = onRequestLocalNetworkPermission,
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                // 4. Secondary Displays
                                PermissionRow(
                                    painter = painterResource(R.drawable.ic_picture_in_picture_off),
                                    title = stringResource(R.string.permission_secondary_displays_title),
                                    description = stringResource(R.string.permission_secondary_displays_desc),
                                    statusText = if (uiState.osAllowSecondaryDisplays) {
                                        stringResource(R.string.permission_secondary_displays_enabled)
                                    } else {
                                        stringResource(R.string.permission_secondary_displays_disabled)
                                    },
                                    statusColor = if (uiState.osAllowSecondaryDisplays) SuccessColor else MaterialTheme.colorScheme.error,
                                    isGranted = uiState.osAllowSecondaryDisplays,
                                    actionLabel = null,
                                    onAction = null,
                                )
                            }
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
                        DefaultStartPageSettingItem(
                            value = uiState.defaultStartPage,
                            onChange = onDefaultStartPageChange,
                        )
                        AppLanguageSettingItem(
                            value = uiState.appLanguage,
                            onChange = onAppLanguageChange,
                        )
                        ToggleSettingItem(
                            name = stringResource(R.string.settings_workbench),
                            description = stringResource(R.string.settings_workbench_note),
                            checked = uiState.workbenchEnabled,
                            onCheckedChange = onWorkbenchEnabledChange,
                        )
                        if (uiState.workbenchEnabled) {
                            WorkbenchAddressInfo(address = uiState.workbenchAddress)
                            WorkbenchPairingSection(
                                uiState = uiState,
                                onStartPairingMode = onStartPairingMode,
                                onStopPairingMode = onStopPairingMode,
                                onSetBruteForceProtection = onSetBruteForceProtection,
                                onRevokeAllTokens = onRevokeAllTokens,
                            )
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

                item {
                    Section(name = stringResource(R.string.settings_about)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onNavigateToAbout)
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.bodyLarge)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
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

@Composable
private fun DefaultStartPageSettingItem(
    value: TopLevelDestination,
    onChange: (TopLevelDestination) -> Unit,
) {
    val labels = TopLevelDestination.entries.associateWith { stringResource(it.labelRes) }
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = stringResource(R.string.settings_default_start_page), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.settings_default_start_page_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        DropdownBox(
            values = TopLevelDestination.entries,
            value = value,
            transform = { labels.getValue(it) },
            onChange = onChange,
        )
    }
}

/** 空字串代表跟隨系統語言；目前只提供 en / zh-TW 兩個選項，對應 app 現有的 values / values-zh-rTW。 */
@Composable
private fun AppLanguageSettingItem(
    value: String,
    onChange: (String) -> Unit,
) {
    val options = listOf(
        "" to stringResource(R.string.settings_language_system_default),
        "en" to stringResource(R.string.settings_language_english),
        "zh-TW" to stringResource(R.string.settings_language_chinese_tw),
    )
    val labels = options.toMap()
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.settings_language_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        DropdownBox(
            values = options.map { it.first },
            value = value,
            transform = { labels.getValue(it) },
            onChange = onChange,
        )
    }
}

@Composable
private fun WorkbenchAddressInfo(address: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (address != null) {
            Text(
                text = stringResource(R.string.settings_workbench_address, address),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.settings_workbench_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkbenchPairingSection(
    uiState: SettingsUiState,
    onStartPairingMode: () -> Unit,
    onStopPairingMode: () -> Unit,
    onSetBruteForceProtection: (Boolean) -> Unit,
    onRevokeAllTokens: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.settings_pairing_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (uiState.isPairingActive) stringResource(R.string.settings_pairing_desc_active) else stringResource(R.string.settings_pairing_desc_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (uiState.isPairingActive) {
                OutlinedButton(onClick = onStopPairingMode) {
                    Text(stringResource(R.string.settings_pairing_action_stop))
                }
            } else {
                Button(onClick = onStartPairingMode) {
                    Text(stringResource(R.string.settings_pairing_action_start))
                }
            }
        }

        if (uiState.isPairingActive && uiState.pairingPin != null) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.settings_pairing_pin_prompt), style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = uiState.pairingPin,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        ToggleSettingItem(
            name = stringResource(R.string.settings_pairing_brute_force_title),
            description = stringResource(R.string.settings_pairing_brute_force_desc),
            checked = uiState.bruteForceProtectionEnabled,
            onCheckedChange = onSetBruteForceProtection,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_pairing_tokens_count, uiState.authorizedTokensCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onRevokeAllTokens,
                enabled = uiState.authorizedTokensCount > 0,
            ) {
                Text(stringResource(R.string.settings_pairing_revoke_tokens))
            }
        }
    }
}

@Composable
private fun PermissionRow(
    painter: Painter? = null,
    imageVector: ImageVector? = null,
    title: String,
    description: String,
    statusText: String,
    statusColor: Color,
    isGranted: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    warningText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(24.dp),
                tint = if (isGranted) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(24.dp),
                tint = if (isGranted) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (warningText != null) {
                Text(
                    text = warningText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onAction,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun SettingsScreenPreview() {
    ReLCTheme {
        Surface {
            var uiState by remember { mutableStateOf(SettingsUiState()) }
            val coroutineScope = rememberCoroutineScope()

            SettingsScreenContent(
                uiState = uiState,
                onRequestShizukuPermission = {
                    when (uiState.shizukuStatus) {
                        ShizukuConnectionStatus.NOT_AVAILABLE -> coroutineScope.launch{

                            uiState = uiState.copy(shizukuStatus = ShizukuConnectionStatus.NEED_PERMISSION)
                        }
                        ShizukuConnectionStatus.NEED_PERMISSION ->
                            uiState = uiState.copy(shizukuStatus = ShizukuConnectionStatus.DISCONNECTED)
                        ShizukuConnectionStatus.DISCONNECTED -> coroutineScope.launch {
                            uiState = uiState.copy(shizukuStatus = ShizukuConnectionStatus.CONNECTING)
                            delay(5000.milliseconds)
                            uiState = uiState.copy(shizukuStatus = ShizukuConnectionStatus.CONNECTED)
                        }
                        else -> {}
                    }
                },
                onRequestNotificationPermission = {
                    uiState = uiState.copy(hasNotificationPermission = true)
                },
                onRefresh = { uiState = SettingsUiState() },
                onAutoOpenFullscreenChange = { isChecked ->
                    uiState = uiState.copy(autoOpenFullscreen = isChecked)
                }
            )
        }
    }
}
