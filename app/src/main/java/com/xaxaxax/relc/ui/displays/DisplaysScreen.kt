package com.xaxaxax.relc.ui.displays

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.ui.component.ShizukuStatusBar
import com.xaxaxax.relc.ui.theme.ReLCTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DisplaysScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: DisplaysViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshDisplays()
    }

    DisplaysScreenContent(
        uiState = uiState,
        onNavigateToDetail = onNavigateToDetail,
        onPullRefresh = { viewModel.refreshDisplays(true) },
        onShizukuAction = { viewModel.onShizukuAction() },
        onDestroyDisplay = { viewModel.destroyDisplay(it) },
        onFabClick = {
            if (uiState.shizukuStatus.isConnected)
                viewModel.createDisplay()
            else {
                Toast.makeText(
                    context,
                    "Shizuku permission required for create display",
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
    onNavigateToDetail: (String) -> Unit,
    onPullRefresh: () -> Unit,
    onShizukuAction: () -> Unit,
    onDestroyDisplay: (Int) -> Unit,
    onFabClick: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        modifier = Modifier,
        topBar = {
            MediumTopAppBar(
                title = { Text("Displays") },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onFabClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Display")
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
                                    text = "No displays created",
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
    ReLCTheme {
        DisplaysScreenContent(
            uiState = DisplaysUiState(
                displays = listOf(
                    DisplayCardInfo(displayId = 1, width = 1080, height = 1920, densityDpi = 320),
                    DisplayCardInfo(displayId = 42, width = 1280, height = 720, densityDpi = 240),
                ),
                shizukuStatus = ShizukuConnectionStatus.CONNECTED,
            ),
            onNavigateToDetail = {},
            onPullRefresh = {},
            onShizukuAction = {},
            onDestroyDisplay = {},
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
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Display #${info.displayId}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${info.width}x${info.height}@${info.densityDpi}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 目前清單只來自 relcV2Service 自己建立的 virtual display，所以恆為管轄中；
            // 之後若改成列出裝置上所有 Display，這裡才需要真的判斷歸屬。
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("relcV2Service 管轄") },
                modifier = Modifier.padding(top = 8.dp)
            )

            Box(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                Column {
                    OutlinedButton(
                        onClick = onClose,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                    Button(
                        onClick = onEnter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text("Enter")
                    }
                }
            }
        }
    }
}
