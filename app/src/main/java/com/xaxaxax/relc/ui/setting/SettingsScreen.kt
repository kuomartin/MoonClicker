package com.xaxaxax.relc.ui.setting

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.R
import com.xaxaxax.relc.TopLevelDestination
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.ui.component.DropdownBox
import com.xaxaxax.relc.ui.component.PermissionRationaleDialog
import com.xaxaxax.relc.ui.component.PermissionRow
import com.xaxaxax.relc.ui.component.Section
import com.xaxaxax.relc.ui.component.ToggleSettingItem
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
    onNavigateToDeveloperOptions: () -> Unit = {},
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
        onStartUserService = viewModel::startUserService,
        onStopUserService = viewModel::stopUserService,
        onRestartUserService = viewModel::restartUserService,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToDeveloperOptions = onNavigateToDeveloperOptions,
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
    onStartUserService: () -> Unit = {},
    onStopUserService: () -> Unit = {},
    onRestartUserService: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDeveloperOptions: () -> Unit = {},
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
                    // Shizuku 授權跟 UserService 本來分兩個區塊各講一次同一個
                    // ShizukuConnectionStatus，使用者要對照兩處才搞得清楚狀況；併成一個。
                    // 這是整個 App 能不能動的前提，排最前面。
                    Section(name = stringResource(R.string.settings_user_service)) {
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
                    Section(name = stringResource(R.string.settings_permissions_title)) {
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

                        // 只有開發人員選項解鎖時才顯示：這個權限只跟 Workbench（開發人員選項
                        // 裡的功能）對外連線有沒有用有關，一般使用者用不到 Workbench 也就不用看到它。
                        if (uiState.isDeveloperOptionsUnlocked) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

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
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToAbout)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }

                if (uiState.isDeveloperOptionsUnlocked) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onNavigateToDeveloperOptions)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.about_developer_options), style = MaterialTheme.typography.bodyLarge)
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
