package com.xaxaxax.moonclicker.ui.developer

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.ui.component.Section
import com.xaxaxax.moonclicker.ui.component.ToggleSettingItem
import com.xaxaxax.moonclicker.ui.setting.SettingsUiState
import com.xaxaxax.moonclicker.ui.setting.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

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
                Section(name = stringResource(R.string.settings_workbench)) {
                    ToggleSettingItem(
                        name = stringResource(R.string.settings_workbench),
                        description = stringResource(R.string.settings_workbench_note),
                        checked = uiState.workbenchEnabled,
                        onCheckedChange = viewModel::setWorkbenchEnabled,
                    )
                    if (uiState.workbenchEnabled) {
                        WorkbenchAddressInfo(address = uiState.workbenchAddress)
                        WorkbenchPairingModeSection(
                            uiState = uiState,
                            onStartPairingMode = viewModel::startPairingMode,
                            onStopPairingMode = viewModel::stopPairingMode,
                            onSetBruteForceProtection = viewModel::setBruteForceProtectionEnabled,
                        )
                    }
                    // 配對憑證是持久資料，跟 server 現在有沒有在跑無關；關掉 Workbench 使用者
                    // 還是要能看到、能清除已配對過的裝置。
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    WorkbenchTokensRow(
                        authorizedTokensCount = uiState.authorizedTokensCount,
                        onRevokeAllTokens = viewModel::revokeAllTokens,
                    )
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

/** PIN 配對需要 server 在跑才有意義，只在 Workbench 開啟時顯示。 */
@Composable
private fun WorkbenchPairingModeSection(
    uiState: SettingsUiState,
    onStartPairingMode: () -> Unit,
    onStopPairingMode: () -> Unit,
    onSetBruteForceProtection: (Boolean) -> Unit,
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
    }
}

/** 已配對憑證是持久資料，跟 Workbench 現在有沒有開都無關，所以獨立於上面那塊，隨時都顯示。 */
@Composable
private fun WorkbenchTokensRow(
    authorizedTokensCount: Int,
    onRevokeAllTokens: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_pairing_tokens_count, authorizedTokensCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = onRevokeAllTokens,
            enabled = authorizedTokensCount > 0,
        ) {
            Text(stringResource(R.string.settings_pairing_revoke_tokens))
        }
    }
}
