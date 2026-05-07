package com.xaxaxax.relc

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.ui.theme.ReLCTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        enableEdgeToEdge()
        setContent {
            ReLCTheme {
                Scaffold(

                    topBar = {
                        TopAppBar(
                            colors = topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.primary,
                            ),
                            title = {
                                Text("Top app bar")
                            }
                        )
                    }
                ) { innerPadding ->
                    Settings(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}


@Composable
fun Settings(viewModel: MainViewModel, modifier: Modifier) {

    // 觀察ViewModel的狀態
    val hasNotificationPermission by viewModel.hasNotificationPermission.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
    val shizukuAvailable by viewModel.shizukuAvailable.collectAsState()
    val hasShizukuPermission by viewModel.hasShizukuPermission.collectAsState()
    // Use in A13 or later
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onNotificationPermissionResult(isGranted)
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissionStatus() // 統一刷新勾勾狀態
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissionStatus()
    }

    Column(modifier) {
        ListItem(
            headlineContent = {
                Text(
                    text = "通知權限", style = MaterialTheme.typography.bodyLarge
                )
            }, supportingContent = {
                Text(
                    text = if (hasNotificationPermission) "已授予" else "需要授予",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasNotificationPermission) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, leadingContent = {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "通知權限",
                    tint = if (hasNotificationPermission) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, trailingContent = {
                if (hasNotificationPermission) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已授予",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "未授予",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }, modifier = if (!hasNotificationPermission) {
                Modifier.clickable {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        settingsLauncher.launch(viewModel.notificationSettingsIntent())
                    }
                }
            } else Modifier)

        HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

        // 懸浮視窗權限
        ListItem(
            headlineContent = {
                Text(
                    text = "懸浮視窗權限", style = MaterialTheme.typography.bodyLarge
                )
            }, supportingContent = {
                Text(
                    text = if (hasOverlayPermission) "已授予" else "需要授予",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasOverlayPermission) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, leadingContent = {
                Icon(
                    imageVector = Icons.Default.DesktopWindows,
                    contentDescription = "懸浮視窗權限",
                    tint = if (hasOverlayPermission) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, trailingContent = {
                if (hasOverlayPermission) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已授予",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "未授予",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }, modifier = if (!hasOverlayPermission) {
                Modifier.clickable {
                    settingsLauncher.launch(
                        viewModel.overlaySettingsIntent()
                    )
                }
            } else Modifier)

        HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

        // Shizuku 服務狀態
        ListItem(
            headlineContent = {
                Text(
                    text = "Shizuku 服務", style = MaterialTheme.typography.bodyLarge
                )
            }, supportingContent = {
                Text(
                    text = if (shizukuAvailable) "可用" else "不可用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (shizukuAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, leadingContent = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Shizuku 服務",
                    tint = if (shizukuAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, trailingContent = {
                if (shizukuAvailable) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "可用",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "不可用",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }, modifier = if (!shizukuAvailable) {
                Modifier.clickable {
                    settingsLauncher.launch(
                        viewModel.openShizukuIntent()
                    )
                }
            } else Modifier)

        HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

        // Shizuku 權限
        ListItem(
            headlineContent = {
                Text(
                    text = "Shizuku 權限", style = MaterialTheme.typography.bodyLarge
                )
            }, supportingContent = {
                Text(
                    text = if (hasShizukuPermission) "已授予" else "需要授予",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasShizukuPermission) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, leadingContent = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Shizuku 權限",
                    tint = if (hasShizukuPermission) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }, trailingContent = {
                if (hasShizukuPermission) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已授予",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "未授予",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }, modifier = if (!hasShizukuPermission && shizukuAvailable) {
                Modifier.clickable {
                    viewModel.requestShizukuPermission()
                }
            } else Modifier)

        Button(
            onClick = { viewModel.grantPermissionByShizuku() },
            enabled = hasShizukuPermission
        ) {
            Text("grantPermissionByShizuku")
        }
    }
}


@Composable
private fun SettingsSection(
    title: String, content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 區塊標題
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // 區塊內容
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 2.dp
            )
        ) {
            content()
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}