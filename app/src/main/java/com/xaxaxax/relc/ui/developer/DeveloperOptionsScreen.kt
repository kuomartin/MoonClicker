package com.xaxaxax.relc.ui.developer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.R
import com.xaxaxax.relc.ui.component.PermissionRationaleDialog
import com.xaxaxax.relc.ui.component.PermissionRow
import com.xaxaxax.relc.ui.component.Section
import com.xaxaxax.relc.ui.component.ToggleSettingItem
import com.xaxaxax.relc.ui.setting.SettingsUiState
import com.xaxaxax.relc.ui.setting.SettingsViewModel
import com.xaxaxax.relc.ui.theme.SuccessColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLocalNetworkRationale by remember { mutableStateOf(false) }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    // targetSdk 37（Android 17）起，接受區網的 inbound TCP 連線需要這個 runtime permission，
    // 不然 VS Code 端連得上 TCP 卻永遠讀不到回應。拒絕不影響 Workbench 開關本身——本機診斷用途
    // 還是能動，只是外部連不進來，不因為這個權限擋住整個功能。
    if (showLocalNetworkRationale) {
        PermissionRationaleDialog(
            title = stringResource(R.string.permission_local_network_rationale_title),
            description = stringResource(R.string.permission_local_network_rationale_desc),
            icon = painterResource(R.drawable.ic_launcher_foreground),
            confirmText = stringResource(R.string.permission_action_proceed),
            dismissText = stringResource(R.string.permission_action_cancel),
            onConfirm = { localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK") },
            onDismiss = { showLocalNetworkRationale = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer_options_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxWidth()) {
            item {
                Section(name = stringResource(R.string.settings_permissions_title)) {
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
                        actionLabel = if (uiState.hasLocalNetworkPermission) null else stringResource(R.string.permission_action_grant),
                        onAction = { showLocalNetworkRationale = true },
                    )
                }
            }

            item {
                Section(name = stringResource(R.string.settings_workbench)) {
                    ToggleSettingItem(
                        name = stringResource(R.string.settings_workbench),
                        description = stringResource(R.string.settings_workbench_note),
                        checked = uiState.workbenchEnabled,
                        onCheckedChange = viewModel::setWorkbenchEnabled,
                    )
                    if (uiState.workbenchEnabled) {
                        WorkbenchAddressInfo(address = uiState.workbenchAddress)
                        WorkbenchPairingSection(
                            uiState = uiState,
                            onStartPairingMode = viewModel::startPairingMode,
                            onStopPairingMode = viewModel::stopPairingMode,
                            onSetBruteForceProtection = viewModel::setBruteForceProtectionEnabled,
                            onRevokeAllTokens = viewModel::revokeAllTokens,
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        viewModel.disableDeveloperOptions()
                        onNavigateBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.developer_options_disable))
                }
            }
        }
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
