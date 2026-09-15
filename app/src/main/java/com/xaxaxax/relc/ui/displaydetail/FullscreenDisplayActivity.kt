package com.xaxaxax.relc.ui.displaydetail

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xaxaxax.relc.ui.theme.ReLCTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import kotlin.math.roundToInt

@AndroidEntryPoint
class FullscreenDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("displayId", -1)
        val scriptDir = intent.getStringExtra("scriptDir") ?: ""
        if (displayId == -1) {
            Timber.e("No displayId provided to FullscreenDisplayActivity")
            finish()
            return
        }

        enableEdgeToEdge()

        // ADR-0014：鏡像釘在面板座標，MainDisplay 自己轉不該讓畫面截圖做動畫。seamless 是
        // hint，系統仍會依八項前提自行退回 CROSSFADE/ROTATE，這裡不用寫 fallback。
        window.attributes = window.attributes.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
        }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            ReLCTheme {
                FullscreenDisplayScreen(displayId, scriptDir)
            }
        }
    }
}

data class AppEntry(val packageName: String, val label: String)

@Composable
fun FullscreenDisplayScreen(
    targetDisplayId: Int,
    scriptDir: String,
    viewModel: FullscreenDisplayViewModel = hiltViewModel()
) {
    val activity = LocalActivity.current
    val uiState by viewModel.uiState.collectAsState()
    val service by viewModel.service.collectAsState()
    val cropState by viewModel.cropSession.state.collectAsState()
    val textureViewRef = remember { mutableStateOf<android.view.TextureView?>(null) }
    // 單一來源：鏡像的 Viewport 讀這一份，決定 letterbox 與內容尺寸。
    val geometry = rememberDisplayGeometry(targetDisplayId)

    // issue #41：退出畫面（返回鍵、Home、或畫面上的 Exit 按鈕，onPause 一律涵蓋）時留一張
    // 縮圖給 Displays 列表用；只在真的有鏡像畫面時才有東西可擷取。
    val currentGeometry by rememberUpdatedState(geometry)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, targetDisplayId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                textureViewRef.value?.bitmap?.let { bitmap ->
                    viewModel.captureThumbnail(targetDisplayId, bitmap, currentGeometry.rotation)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // issue #76：workbench 的 `/mirror/{displayId}` 也從這個 TextureView 取畫面。擷取只取原始
    // buffer，轉正與 JPEG 編碼在 MirrorFrameSource 的背景執行緒做，不佔 UI thread。
    val mirrorTextureView = textureViewRef.value
    DisposableEffect(targetDisplayId, mirrorTextureView) {
        if (mirrorTextureView != null) {
            viewModel.mirrorFrames.register(targetDisplayId) {
                mirrorTextureView.bitmap?.let {
                    MirrorFrameSource.Captured(it, currentGeometry.rotation)
                }
            }
        }
        onDispose { viewModel.mirrorFrames.unregister(targetDisplayId) }
    }

    // ADR-0014：鏡像釘在 MainDisplay 的面板座標，不再跟 VD 的方向互相牽制——VD 怎麼轉
    // 是它自己的事，這個 activity 也不再把 VD 的方向鎖進 requestedOrientation。

    LaunchedEffect(cropState.isActive) {
        if (cropState.isActive && cropState.bitmap == null) {
            // TextureView 取代 SurfaceView 之後，擷取畫面不需要 PixelCopy —— getBitmap()
            // 直接同步回傳 texture 的內容，但那是 surface 空間（未套用 VD 自己的 rotation）。
            // ADR-0013：模板圖是邏輯空間的產物，所以裁切前先用 rotateBufferBitmap 轉正——
            // 跟 DisplayThumbnailCache.put 存縮圖用的是同一個轉換，理由也一樣。
            val bitmap = textureViewRef.value?.bitmap?.let { rotateBufferBitmap(it, geometry.rotation) }
            if (bitmap != null) {
                viewModel.cropSession.setCapturedBitmap(bitmap)
            } else {
                Timber.e("TextureView.getBitmap() returned null")
                viewModel.cropSession.cancel()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (service != null) {
            VirtualDisplayMirror(
                targetDisplayId = targetDisplayId,
                geometry = geometry,
                addSurface = { viewModel.addSurface(targetDisplayId, it) },
                removeSurface = { viewModel.removeSurface(targetDisplayId, it) },
                service = service!!,
                isReadOnly = uiState.isReadOnly,
                modifier = Modifier.fillMaxSize(),
                onTextureViewCreated = { textureViewRef.value = it },
                onFrameAvailable = { viewModel.mirrorFrames.onFrameAvailable(targetDisplayId) },
            )
        }

        if (cropState.isActive) {
            CropScreen(session = viewModel.cropSession, scriptDir = scriptDir)
        } else {
            // Floating Control Bar
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
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.8f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        // Screenshot
                        IconButton(onClick = { viewModel.startCropping() }) {
                            Icon(
                                imageVector = Icons.Default.Crop,
                                contentDescription = "Screenshot",
                                tint = Color.White
                            )
                        }

                        // Exit
                        IconButton(onClick = { activity?.finish() }) {
                            Icon(
                                imageVector = Icons.Default.ExitToApp,
                                contentDescription = "Exit",
                                tint = Color.White
                            )
                        }

                        // More Menu
                        Box {
                            IconButton(onClick = { viewModel.setMenuExpanded(true) }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = Color.White
                                )
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
                    }
                }
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
