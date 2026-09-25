package com.xaxaxax.moonclicker.ui.displays

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.shizuku.ShizukuConnectionStatus
import com.xaxaxax.moonclicker.ui.component.ShizukuStatusBar
import com.xaxaxax.moonclicker.ui.theme.MoonClickerTheme

const val MIN_DISPLAY_DIMENSION_PX = 100
const val MAX_DISPLAY_DIMENSION_PX = 7680
const val MIN_DISPLAY_DPI = 120
const val MAX_DISPLAY_DPI = 640

fun isValidDisplayDimension(text: String): Boolean {
    val value = text.toIntOrNull() ?: return false
    return value in MIN_DISPLAY_DIMENSION_PX..MAX_DISPLAY_DIMENSION_PX
}

fun isValidDisplayDpi(text: String): Boolean {
    val value = text.toIntOrNull() ?: return false
    return value in MIN_DISPLAY_DPI..MAX_DISPLAY_DPI
}

data class DisplayPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DisplaysScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: DisplaysViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // FullscreenDisplayActivity 是另一個 Activity，不是 nav destination：從它返回時這個
    // composable沒有離開過 composition，LaunchedEffect(Unit) 不會重跑，縮圖/已關閉的顯示器
    // 都要等下一次 onResume 才看得到——掛在 onResume 上才能涵蓋每次回到這個畫面的情況。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshDisplays()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showShizukuRationale by remember { mutableStateOf(false) }

    if (showShizukuRationale) {
        com.xaxaxax.moonclicker.ui.component.PermissionRationaleDialog(
            title = androidx.compose.ui.res.stringResource(com.xaxaxax.moonclicker.R.string.permission_shizuku_rationale_title),
            description = androidx.compose.ui.res.stringResource(com.xaxaxax.moonclicker.R.string.permission_shizuku_rationale_desc),
            icon = androidx.compose.ui.res.painterResource(com.xaxaxax.moonclicker.R.drawable.ic_shizuku_icon),
            onConfirm = { viewModel.onShizukuAction() },
            onDismiss = { showShizukuRationale = false },
        )
    }

    val shizukuRequiredMessage = stringResource(R.string.displays_shizuku_required)

    DisplaysScreenContent(
        uiState = uiState,
        defaultWidth = viewModel.defaultConfig.width,
        defaultHeight = viewModel.defaultConfig.height,
        defaultDensityDpi = viewModel.defaultConfig.densityDpi,
        onNavigateToDetail = onNavigateToDetail,
        onPullRefresh = { viewModel.refreshDisplays(true) },
        onShizukuAction = {
            if (uiState.shizukuStatus == ShizukuConnectionStatus.NEED_PERMISSION) {
                showShizukuRationale = true
            } else {
                viewModel.onShizukuAction()
            }
        },
        onDestroyDisplay = { viewModel.destroyDisplay(it) },
        onToggleMirror = { displayId, enable -> viewModel.toggleMirror(displayId, enable) },
        onCreateDisplay = { width, height, densityDpi ->
            viewModel.createDisplay(
                viewModel.defaultConfig.copy(width = width, height = height, densityDpi = densityDpi)
            )
        },
        onFabClick = {
            if (!uiState.shizukuStatus.isConnected) {
                Toast.makeText(
                    context,
                    shizukuRequiredMessage,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DisplaysScreenContent(
    uiState: DisplaysUiState,
    defaultWidth: Int,
    defaultHeight: Int,
    defaultDensityDpi: Int,
    onNavigateToDetail: (String) -> Unit,
    onPullRefresh: () -> Unit,
    onShizukuAction: () -> Unit,
    onDestroyDisplay: (Int) -> Unit,
    onToggleMirror: (Int, Boolean) -> Unit,
    onCreateDisplay: (width: Int, height: Int, densityDpi: Int) -> Unit,
    onFabClick: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullRefreshState = rememberPullToRefreshState()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateDisplayDialog(
            defaultWidth = defaultWidth,
            defaultHeight = defaultHeight,
            defaultDensityDpi = defaultDensityDpi,
            onDismiss = { showCreateDialog = false },
            onConfirm = { width, height, densityDpi ->
                showCreateDialog = false
                onCreateDisplay(width, height, densityDpi)
            }
        )
    }

    Scaffold(
        modifier = Modifier,
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.displays_title)) },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (uiState.shizukuStatus.isConnected) showCreateDialog = true
                    else onFabClick()
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.displays_create))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ShizukuStatusBar(
                status = uiState.shizukuStatus,
                onActionClick = onShizukuAction,
            )
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onPullRefresh,
                state = pullRefreshState,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullRefreshState,
                        isRefreshing = uiState.isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
                enabled = uiState.shizukuStatus.isConnected,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.displays.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.displays_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(uiState.displays, key = { it.displayId }) { info ->
                        DisplayCard(
                            info = info,
                            onEnter = { onNavigateToDetail(info.displayId.toString()) },
                            onClose = { onDestroyDisplay(info.displayId) },
                            onToggleMirror = { enable -> onToggleMirror(info.displayId, enable) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Preview(showBackground = true)
@Composable
private fun PreviewDisplaysScreen() {
    MoonClickerTheme {
        DisplaysScreenContent(
            uiState = DisplaysUiState(
                displays = listOf(
                    DisplayCardInfo(displayId = 0, name = "Built-in Screen", width = 1080, height = 2400, densityDpi = 420, isPhysical = true, isMirrorActive = false),
                    DisplayCardInfo(displayId = 1, width = 1080, height = 1920, densityDpi = 320, isManaged = true),
                    DisplayCardInfo(displayId = 42, width = 1280, height = 720, densityDpi = 240),
                ),
                shizukuStatus = ShizukuConnectionStatus.CONNECTED,
            ),
            defaultWidth = 1080,
            defaultHeight = 1920,
            defaultDensityDpi = 320,
            onNavigateToDetail = {},
            onPullRefresh = {},
            onShizukuAction = {},
            onDestroyDisplay = {},
            onToggleMirror = { _, _ -> },
            onCreateDisplay = { _, _, _ -> },
            onFabClick = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayCard(
    info: DisplayCardInfo,
    onEnter: () -> Unit,
    onClose: () -> Unit,
    onToggleMirror: (Boolean) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        // 縮圖本身就是主要動作入口：本服務建立的虛擬顯示／鏡像中的顯示點下去 = Enter，
        // 需要鏡像但還沒鏡像的（實體螢幕、外部虛擬螢幕）點下去 = Start Mirror。右上角疊一顆 X
        // 做次要動作（本服務建立的虛擬顯示 = Close，鏡像中 = Stop Mirror），未鏡像時沒有次要動作可疊。
        val primaryLabel = if (info.needsMirror && !info.isMirrorActive) {
            stringResource(R.string.displays_action_start_mirror)
        } else {
            stringResource(R.string.displays_action_enter)
        }
        val primaryAction = if (info.needsMirror && !info.isMirrorActive) {
            { onToggleMirror(true) }
        } else {
            onEnter
        }
        // 縮圖貼齊卡片邊緣、不留 padding；只有上緣兩個角要跟著卡片本身的圓角走，
        // 下緣是直角，緊接著下面的文字內容。
        val thumbnailShape = (MaterialTheme.shapes.extraLarge as RoundedCornerShape).copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
        // Box 固定高度、縮圖缺席時也保留：同一列的卡片才不會因為有沒有縮圖而高低不齊。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(thumbnailShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = primaryLabel, onClick = primaryAction),
            contentAlignment = Alignment.Center,
        ) {
            if (info.thumbnail != null) {
                Image(
                    bitmap = info.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (info.needsMirror && !info.isMirrorActive) {
                Text(
                    text = stringResource(R.string.displays_card_mirror_not_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (info.isManaged) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.displays_action_close),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (info.isMirrorActive) {
                IconButton(
                    onClick = { onToggleMirror(false) },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.displays_action_stop_mirror),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(start = 16.dp,end = 16.dp,top = 8.dp,  bottom = 12.dp)
        ) {
            Text(
                text = when {
                    info.isExternal -> stringResource(R.string.displays_card_external, info.displayId)
                    info.isPhysical -> stringResource(R.string.displays_card_physical, info.displayId)
                    else -> stringResource(R.string.displays_card_virtual, info.displayId)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${info.width}x${info.height}@${info.densityDpi}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        when {
                            info.isExternal && info.isMirrorActive -> stringResource(R.string.displays_card_tag_external_mirroring)
                            info.isExternal -> stringResource(R.string.displays_card_tag_external)
                            info.isPhysical && info.isMirrorActive -> stringResource(R.string.displays_card_tag_physical_mirroring)
                            info.isPhysical -> stringResource(R.string.displays_card_tag_physical)
                            else -> stringResource(R.string.displays_card_tag_service)
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun CreateDisplayDialog(
    defaultWidth: Int,
    defaultHeight: Int,
    defaultDensityDpi: Int,
    onDismiss: () -> Unit,
    onConfirm: (width: Int, height: Int, densityDpi: Int) -> Unit,
) {
    val localLabel = stringResource(R.string.displays_preset_local)
    val smallLabel = stringResource(R.string.displays_preset_small)
    val mediumLabel = stringResource(R.string.displays_preset_medium)
    val tabletLabel = stringResource(R.string.displays_preset_tablet)
    val presets = remember(defaultWidth, defaultHeight, defaultDensityDpi, localLabel, smallLabel, mediumLabel, tabletLabel) {
        listOf(
            DisplayPreset(localLabel, defaultWidth, defaultHeight, defaultDensityDpi),
            DisplayPreset(smallLabel, 720, 1280, 320),
            DisplayPreset(mediumLabel, 1080, 2400, 420),
            DisplayPreset(tabletLabel, 2560, 1600, 320),
        )
    }
    var widthText by rememberSaveable { mutableStateOf(defaultWidth.toString()) }
    var heightText by rememberSaveable { mutableStateOf(defaultHeight.toString()) }
    var dpiText by rememberSaveable { mutableStateOf(defaultDensityDpi.toString()) }

    val widthValid = isValidDisplayDimension(widthText)
    val heightValid = isValidDisplayDimension(heightText)
    val dpiValid = isValidDisplayDpi(dpiText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.displays_create_dialog_title)) },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    presets.forEach { preset ->
                        val selected = widthText == preset.width.toString() &&
                            heightText == preset.height.toString() &&
                            dpiText == preset.densityDpi.toString()
                        FilterChip(
                            selected = selected,
                            onClick = {
                                widthText = preset.width.toString()
                                heightText = preset.height.toString()
                                dpiText = preset.densityDpi.toString()
                            },
                            label = { Text(preset.label) },
                            contentPadding = PaddingValues(0.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it },
                    label = { Text(stringResource(R.string.displays_create_width)) },
                    isError = !widthValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text(stringResource(R.string.displays_create_height)) },
                    isError = !heightValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = dpiText,
                    onValueChange = { dpiText = it },
                    label = { Text(stringResource(R.string.displays_create_density)) },
                    isError = !dpiValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    text = stringResource(
                        R.string.displays_create_range_hint,
                        MIN_DISPLAY_DIMENSION_PX,
                        MAX_DISPLAY_DIMENSION_PX,
                        MIN_DISPLAY_DPI,
                        MAX_DISPLAY_DPI
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(widthText.toInt(), heightText.toInt(), dpiText.toInt()) },
                enabled = widthValid && heightValid && dpiValid
            ) {
                Text(stringResource(R.string.common_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
