package com.xaxaxax.relc.ui.displaydetail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.ui.theme.ReLCTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import kotlin.math.roundToInt

@AndroidEntryPoint
class FullscreenDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("displayId", -1)
        if (displayId == -1) {
            Timber.e("No displayId provided to FullscreenDisplayActivity")
            finish()
            return
        }

        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            ReLCTheme {
                FullscreenDisplayScreen(displayId)
            }
        }
    }
}

data class AppEntry(val packageName: String, val label: String)

@Composable
fun FullscreenDisplayScreen(
    targetDisplayId: Int,
    viewModel: FullscreenDisplayViewModel = hiltViewModel()
) {
    val activity = LocalActivity.current
    val uiState by viewModel.uiState.collectAsState()
    val inputController by viewModel.inputController.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (inputController != null) {
            val metrics = LocalResources.current.displayMetrics
            val config = DisplayConfig(
                name = "Attached",
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi
            )
            VirtualDisplaySurfaceView(
                targetDisplayId = targetDisplayId,
                addSurface = { viewModel.addSurface(targetDisplayId, it) },
                removeSurface = { viewModel.removeSurface(targetDisplayId, it) },
                inputController = inputController!!,
                config = config,
                isReadOnly = uiState.isReadOnly,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Floating Control Menu
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        uiState.menuOffsetX.roundToInt(),
                        uiState.menuOffsetY.roundToInt()
                    )
                }
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        viewModel.updateMenuOffset(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(16.dp)
        ) {
            FloatingActionButton(
                onClick = { viewModel.setMenuExpanded(true) },
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }

            DropdownMenu(
                expanded = uiState.menuExpanded,
                onDismissRequest = { viewModel.setMenuExpanded(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Start App") },
                    onClick = {
                        viewModel.setMenuExpanded(false)
                        viewModel.openAppList()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (uiState.isReadOnly) "Disable Read-Only" else "Enable Read-Only") },
                    onClick = {
                        viewModel.toggleReadOnly()
                        viewModel.setMenuExpanded(false)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Close Display") },
                    onClick = {
                        viewModel.setMenuExpanded(false)
                        viewModel.destroyDisplay(targetDisplayId)
                        activity?.finish()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Return to ReLC") },
                    onClick = {
                        activity?.finish()
                        viewModel.setMenuExpanded(false)
                    }
                )
            }
        }

        if (uiState.showAppList) {
            AlertDialog(
                onDismissRequest = { viewModel.closeAppList() },
                title = { Text("Select App to Start") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(uiState.apps) { app ->
                            TextButton(
                                onClick = {
                                    viewModel.launchApp(app.packageName, targetDisplayId)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(app.label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.closeAppList() }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
