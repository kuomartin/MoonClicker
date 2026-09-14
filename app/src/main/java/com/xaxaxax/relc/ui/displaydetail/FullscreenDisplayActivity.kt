package com.xaxaxax.relc.ui.displaydetail

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
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

    // ADR-0014：鏡像釘在 MainDisplay 的面板座標，不再跟 VD 的方向互相牽制——VD 怎麼轉
    // 是它自己的事，這個 activity 也不再把 VD 的方向鎖進 requestedOrientation。

    LaunchedEffect(uiState.executionState) {
        if (uiState.executionState == FullscreenDisplayViewModel.ExecutionState.CROPPING && capturedBitmap == null) {
            // TextureView 取代 SurfaceView 之後，擷取畫面不需要 PixelCopy —— getBitmap()
            // 直接同步回傳 texture 的內容。注意它回傳的是**未套用 view 旋轉**的影格，
            // 亦即 surface 空間；虛擬顯示旋轉時模板的座標系該怎麼算，見地圖 #9 的迷霧。
            val bitmap = textureViewRef.value?.bitmap
            if (bitmap != null) {
                viewModel.setCapturedBitmap(bitmap)
            } else {
                Timber.e("TextureView.getBitmap() returned null")
                viewModel.cancelCropping()
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
                onTextureViewCreated = { textureViewRef.value = it }
            )
        }

        if (uiState.executionState == FullscreenDisplayViewModel.ExecutionState.CROPPING) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (capturedBitmap != null) {
                    val density = LocalDensity.current
                    val handleRadius = with(density) { 24.dp.toPx() }
                    var cropRect by remember { mutableStateOf<CropRect?>(null) }
                    var activeHandle by remember { mutableStateOf(DragHandle.None) }
                    var showSaveDialog by remember { mutableStateOf(false) }
                    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        activeHandle = hitTest(cropRect, offset.x, offset.y, handleRadius)
                                        if (activeHandle == DragHandle.None) {
                                            cropRect = CropRect(offset.x, offset.y, offset.x, offset.y)
                                            activeHandle = DragHandle.BottomRight
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val r = cropRect ?: return@detectDragGestures
                                        cropRect = dragResize(r, activeHandle, dragAmount.x, dragAmount.y)
                                    },
                                    onDragEnd = {
                                        cropRect = cropRect?.normalized()
                                        activeHandle = DragHandle.None
                                    }
                                )
                            }
                    ) {
                        canvasSize = IntSize(size.width.toInt(), size.height.toInt())

                        drawImage(
                            image = capturedBitmap!!.asImageBitmap(),
                            dstSize = canvasSize
                        )
                        drawRect(Color.Black.copy(alpha = 0.5f))

                        cropRect?.let { rect ->
                            val topLeft = Offset(rect.left, rect.top)
                            val rectSize = Size(rect.width, rect.height)
                            drawRect(
                                color = Color.Transparent,
                                topLeft = topLeft,
                                size = rectSize,
                                blendMode = BlendMode.Clear
                            )
                            drawRect(
                                color = Color.Red,
                                topLeft = topLeft,
                                size = rectSize,
                                style = Stroke(width = 4f)
                            )
                            drawCircle(Color.Red, radius = 10f, center = Offset(rect.left, rect.top))
                            drawCircle(Color.Red, radius = 10f, center = Offset(rect.right, rect.top))
                            drawCircle(Color.Red, radius = 10f, center = Offset(rect.left, rect.bottom))
                            drawCircle(Color.Red, radius = 10f, center = Offset(rect.right, rect.bottom))
                        }
                    }

                    if (showSaveDialog && cropRect != null) {
                        var templateName by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showSaveDialog = false },
                            title = { Text("Save Template") },
                            text = {
                                OutlinedTextField(
                                    value = templateName,
                                    onValueChange = { templateName = it },
                                    label = { Text("Template Name") }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    activity ?: return@TextButton
                                    val pixelRect = cropRect?.toPixelRect(
                                        canvasWidth = canvasSize.width.toFloat(),
                                        canvasHeight = canvasSize.height.toFloat(),
                                        bitmapWidth = capturedBitmap!!.width,
                                        bitmapHeight = capturedBitmap!!.height,
                                    )
                                    if (pixelRect != null) {
                                        val cRect = android.graphics.Rect(
                                            pixelRect.left, pixelRect.top, pixelRect.right, pixelRect.bottom
                                        )
                                        viewModel.saveCroppedImage(scriptDir, templateName, cRect)
                                    }
                                    showSaveDialog = false
                                }) { Text("Save") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton(
                            onClick = { viewModel.cancelCropping() },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                            Text("Cancel")
                        }
                        if (cropRect != null && cropRect!!.width > 0 && cropRect!!.height > 0) {
                            Button(
                                onClick = { showSaveDialog = true },
                            ) {
                                Text("Save Crop")
                            }
                        }
                    }
                } else {                    Text(
                        text = "Capturing...",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    TextButton(
                        onClick = { viewModel.cancelCropping() },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            }
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
