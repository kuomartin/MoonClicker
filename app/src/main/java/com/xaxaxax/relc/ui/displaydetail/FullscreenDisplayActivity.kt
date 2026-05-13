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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.input.InputController
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import com.xaxaxax.relc.ui.theme.ReLCTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
                FullscreenDisplayScreen(displayId = displayId)
            }
        }
    }
}

@Composable
fun FullscreenDisplayScreen(displayId: Int) {
    val activity = LocalActivity.current
    val intent = activity?.intent
    val scope = rememberCoroutineScope()
    val serviceFlow = remember {
        UserService.create(
            scope,
            RelcShizukuService::class,
            IRelcShizukuService.Stub::asInterface
        )
    }

    val controllerState = remember { MutableStateFlow<VirtualDisplayController?>(null) }
    val inputControllerState = remember { MutableStateFlow<InputController?>(null) }

    val controller by controllerState.collectAsState()
    val inputController by inputControllerState.collectAsState()

    var isReadOnly by remember {
        mutableStateOf(intent?.getBooleanExtra("isReadOnly", false) ?: false)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var menuOffsetX by remember { mutableFloatStateOf(0f) }
    var menuOffsetY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(displayId) {
        scope.launch {
            serviceFlow.runWhenAlive { service ->
                val ctrl = VirtualDisplayController(service)
                ctrl.attach(displayId)
                controllerState.value = ctrl
                inputControllerState.value = InputController(service)
            }
        }

        onDispose {
            controllerState.value = null
            inputControllerState.value = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (controller != null && inputController != null) {
            val metrics = LocalResources.current.displayMetrics
            val config = DisplayConfig(
                name = "Attached",
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi
            )
            VirtualDisplaySurfaceView(
                controller = controller!!,
                inputController = inputController!!,
                config = config,
                isReadOnly = isReadOnly,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Floating Control Menu
        Box(
            modifier = Modifier
                .offset { IntOffset(menuOffsetX.roundToInt(), menuOffsetY.roundToInt()) }
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        menuOffsetX += dragAmount.x
                        menuOffsetY += dragAmount.y
                    }
                }
                .padding(16.dp)
        ) {
            FloatingActionButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (isReadOnly) "Disable Read-Only" else "Enable Read-Only") },
                    onClick = {
                        isReadOnly = !isReadOnly
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Launch Home") },
                    onClick = {
                        scope.launch {
                            serviceFlow.runWhenAlive { service ->
                                service.launchHome(displayId)
                            }
                        }
                        menuExpanded = false
                    }
                )
            }
        }
    }
}
