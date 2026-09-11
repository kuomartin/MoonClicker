package com.xaxaxax.relc.ui.displaydetail

import android.os.Bundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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

enum class DragHandle {
    TopLeft, TopRight, BottomLeft, BottomRight,
    Top, Bottom, Left, Right, Center, None
}

@Composable
fun FullscreenDisplayScreen(
    targetDisplayId: Int,
    scriptDir: String,
    viewModel: FullscreenDisplayViewModel = hiltViewModel()
) {
    val activity = LocalActivity.current
    val uiState by viewModel.uiState.collectAsState()
    val inputController by viewModel.inputController.collectAsState()
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val textureViewRef = remember { mutableStateOf<android.view.TextureView?>(null) }
    // 單一來源：鏡像的 Viewport 與（#17 之後）X 的 requestedOrientation 都讀這一份。
    val geometry = rememberDisplayGeometry(targetDisplayId)

    // 方向鏈 Y → VD → X → MainDisplay（#17）。系統負責 WM 仲裁與 MainDisplay 跟隨 X，
    // 這裡只接兩環：感測器推給虛擬顯示，以及虛擬顯示的 rotation 推給 X。
    SensorRotationDriver(geometry.rotation) { rotation ->
        viewModel.setDisplayRotation(targetDisplayId, rotation)
    }
    FollowDisplayRotation(activity, geometry.rotation)

    var showTemplateSelector by remember { mutableStateOf(false) }
    var availableTemplates by remember { mutableStateOf<List<String>>(emptyList()) }

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
        if (inputController != null) {
            VirtualDisplayMirror(
                targetDisplayId = targetDisplayId,
                geometry = geometry,
                addSurface = { viewModel.addSurface(targetDisplayId, it) },
                removeSurface = { viewModel.removeSurface(targetDisplayId, it) },
                inputController = inputController!!,
                isReadOnly = uiState.isReadOnly,
                modifier = Modifier.fillMaxSize(),
                onTextureViewCreated = { textureViewRef.value = it }
            )
        }

        if (uiState.executionState == FullscreenDisplayViewModel.ExecutionState.CROPPING) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (capturedBitmap != null) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val handleRadius = with(density) { 24.dp.toPx() }
                    var cropRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                    var activeHandle by remember { mutableStateOf(DragHandle.None) }
                    var showSaveDialog by remember { mutableStateOf(false) }
                    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

                    fun getHandle(offset: androidx.compose.ui.geometry.Offset, rect: androidx.compose.ui.geometry.Rect?): DragHandle {
                        if (rect == null) return DragHandle.None
                        val near = { a: androidx.compose.ui.geometry.Offset, b: androidx.compose.ui.geometry.Offset -> (a - b).getDistance() < handleRadius }
                        val nearX = { x: Float -> Math.abs(offset.x - x) < handleRadius }
                        val nearY = { y: Float -> Math.abs(offset.y - y) < handleRadius }

                        val normRect = androidx.compose.ui.geometry.Rect(
                            left = minOf(rect.left, rect.right),
                            top = minOf(rect.top, rect.bottom),
                            right = maxOf(rect.left, rect.right),
                            bottom = maxOf(rect.top, rect.bottom)
                        )

                        return when {
                            near(offset, normRect.topLeft) -> DragHandle.TopLeft
                            near(offset, normRect.topRight) -> DragHandle.TopRight
                            near(offset, normRect.bottomLeft) -> DragHandle.BottomLeft
                            near(offset, normRect.bottomRight) -> DragHandle.BottomRight
                            nearX(normRect.left) && offset.y in normRect.top..normRect.bottom -> DragHandle.Left
                            nearX(normRect.right) && offset.y in normRect.top..normRect.bottom -> DragHandle.Right
                            nearY(normRect.top) && offset.x in normRect.left..normRect.right -> DragHandle.Top
                            nearY(normRect.bottom) && offset.x in normRect.left..normRect.right -> DragHandle.Bottom
                            normRect.contains(offset) -> DragHandle.Center
                            else -> DragHandle.None
                        }
                    }

                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        activeHandle = getHandle(offset, cropRect)
                                        if (activeHandle == DragHandle.None) {
                                            cropRect = androidx.compose.ui.geometry.Rect(offset, offset)
                                            activeHandle = DragHandle.BottomRight
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val r = cropRect ?: return@detectDragGestures
                                        cropRect = when (activeHandle) {
                                            DragHandle.TopLeft -> androidx.compose.ui.geometry.Rect(r.left + dragAmount.x, r.top + dragAmount.y, r.right, r.bottom)
                                            DragHandle.TopRight -> androidx.compose.ui.geometry.Rect(r.left, r.top + dragAmount.y, r.right + dragAmount.x, r.bottom)
                                            DragHandle.BottomLeft -> androidx.compose.ui.geometry.Rect(r.left + dragAmount.x, r.top, r.right, r.bottom + dragAmount.y)
                                            DragHandle.BottomRight -> androidx.compose.ui.geometry.Rect(r.left, r.top, r.right + dragAmount.x, r.bottom + dragAmount.y)
                                            DragHandle.Top -> androidx.compose.ui.geometry.Rect(r.left, r.top + dragAmount.y, r.right, r.bottom)
                                            DragHandle.Bottom -> androidx.compose.ui.geometry.Rect(r.left, r.top, r.right, r.bottom + dragAmount.y)
                                            DragHandle.Left -> androidx.compose.ui.geometry.Rect(r.left + dragAmount.x, r.top, r.right, r.bottom)
                                            DragHandle.Right -> androidx.compose.ui.geometry.Rect(r.left, r.top, r.right + dragAmount.x, r.bottom)
                                            DragHandle.Center -> androidx.compose.ui.geometry.Rect(r.left + dragAmount.x, r.top + dragAmount.y, r.right + dragAmount.x, r.bottom + dragAmount.y)
                                            DragHandle.None -> r
                                        }
                                    },
                                    onDragEnd = {
                                        cropRect?.let { r ->
                                            cropRect = androidx.compose.ui.geometry.Rect(
                                                left = minOf(r.left, r.right),
                                                top = minOf(r.top, r.bottom),
                                                right = maxOf(r.left, r.right),
                                                bottom = maxOf(r.top, r.bottom)
                                            )
                                        }
                                        activeHandle = DragHandle.None
                                    }
                                )
                            }
                    ) {
                        canvasSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())

                        // Draw image
                        drawImage(
                            image = capturedBitmap!!.asImageBitmap(),
                            dstSize = canvasSize
                        )
                        // Draw dim overlay
                        drawRect(Color.Black.copy(alpha = 0.5f))

                        if (cropRect != null) {
                            val rect = cropRect!!
                            // clear the dim overlay in the rect
                            drawRect(
                                color = Color.Transparent,
                                topLeft = rect.topLeft,
                                size = rect.size,
                                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                            )
                            // Draw border
                            drawRect(
                                color = Color.Red,
                                topLeft = rect.topLeft,
                                size = rect.size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                            )
                            // Draw handles (circles at corners)
                            drawCircle(Color.Red, radius = 10f, center = rect.topLeft)
                            drawCircle(Color.Red, radius = 10f, center = rect.topRight)
                            drawCircle(Color.Red, radius = 10f, center = rect.bottomLeft)
                            drawCircle(Color.Red, radius = 10f, center = rect.bottomRight)
                        }
                    }

                    if (showSaveDialog && cropRect != null) {
                        var templateName by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showSaveDialog = false },
                            title = { Text("Save Template") },
                            text = {
                                androidx.compose.material3.OutlinedTextField(
                                    value = templateName,
                                    onValueChange = { templateName = it },
                                    label = { Text("Template Name") }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val context = activity ?: return@TextButton
                                    if (canvasSize.width > 0 && canvasSize.height > 0) {
                                        val scaleX = capturedBitmap!!.width.toFloat() / canvasSize.width
                                        val scaleY = capturedBitmap!!.height.toFloat() / canvasSize.height

                                        val rect = cropRect!!
                                        val cRect = android.graphics.Rect(
                                            (rect.left * scaleX).toInt(),
                                            (rect.top * scaleY).toInt(),
                                            (rect.right * scaleX).toInt(),
                                            (rect.bottom * scaleY).toInt()
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
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton(
                            onClick = { viewModel.cancelCropping() },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                            Text("Cancel")
                        }
                        if (cropRect != null && cropRect!!.width > 0 && cropRect!!.height > 0) {
                            androidx.compose.material3.Button(
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
                        // Start/Stop
                        IconButton(onClick = {
                            if (uiState.executionState == FullscreenDisplayViewModel.ExecutionState.RUNNING) {
                                viewModel.stopExecution()
                            } else {
                                // 影格尺寸必須是虛擬顯示的 surface 尺寸，不是手機的 metrics（#19）。
                                viewModel.startExecution(
                                    targetDisplayId,
                                    geometry.surfaceWidth,
                                    geometry.surfaceHeight,
                                    scriptDir,
                                )
                            }
                        }) {
                            Icon(
                                imageVector = if (uiState.executionState == FullscreenDisplayViewModel.ExecutionState.RUNNING)
                                    Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.executionState == FullscreenDisplayViewModel.ExecutionState.RUNNING)
                                    "Stop" else "Start",
                                tint = Color.White
                            )
                        }

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
                                    text = { Text("Test Template") },
                                    onClick = {
                                        viewModel.setMenuExpanded(false)
                                        val dir = java.io.File(scriptDir)
                                        if (dir.exists()) {
                                            availableTemplates = dir.listFiles { _, name -> name.endsWith(".png") }
                                                ?.map { it.name } ?: emptyList()
                                            showTemplateSelector = true
                                        }
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

        if (showTemplateSelector) {
            AlertDialog(
                onDismissRequest = { showTemplateSelector = false },
                title = { Text("Select Template to Test") },
                text = {
                    if (availableTemplates.isEmpty()) {
                        Text("No templates found in script directory.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            items(availableTemplates) { template ->
                                TextButton(
                                    onClick = {
                                        viewModel.startTemplateTest(
                                            targetDisplayId,
                                            geometry.surfaceWidth,
                                            geometry.surfaceHeight,
                                            scriptDir,
                                            template
                                        )
                                        showTemplateSelector = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(template, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTemplateSelector = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
