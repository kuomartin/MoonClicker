package com.xaxaxax.relc.ui.displaydetail

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.xaxaxax.relc.IRelcV2Service
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * 把鏡像 view 的生命週期跟虛擬顯示串接起來，並攔截觸控事件轉發給虛擬顯示（[forwardMirrorTouch]）。
 *
 * 呈現的 letterbox 幾何全部來自單一的 [Viewport]（見地圖 #9 / #12），只看 d（MainDisplay
 * 的 rotation）。觸控多疊一層 [touchTransform]：先用 [Viewport.displayPerViewPixel] 縮放到
 * buffer 像素，再疊上 v（VD 自己的 rotation）對應的旋轉，才是 `injectMotionEvent` 要的
 * VD 邏輯空間（ADR-0014）。兩者的輸入都取自公開的 `Display` API，不需要任何 AIDL。
 */
@Composable
fun VirtualDisplayMirror(
    targetDisplayId: Int,
    geometry: DisplayGeometry,
    addSurface: (Surface) -> Unit,
    removeSurface: (Surface) -> Unit,
    service: IRelcV2Service,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier,
    onTextureViewCreated: (TextureView) -> Unit = {},
) {
    BoxWithConstraints(modifier.background(Color.Black)) {
        // ADR-0014：letterbox 尺寸與反向旋轉都只看 d（MainDisplay 的 rotation），
        // 跟 VD 自己的 rotation 無關——VD 的 buffer 尺寸不隨它自己的旋轉改變。
        val hostRotation = rememberHostDisplayRotation()
        val viewport = viewportOf(
            surfaceWidth = geometry.surfaceWidth,
            surfaceHeight = geometry.surfaceHeight,
            d = hostRotation,
            viewWidth = constraints.maxWidth,
            viewHeight = constraints.maxHeight,
        )
        if (viewport.isEmpty) return@BoxWithConstraints

        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(viewport.contentLeft.roundToInt(), viewport.contentTop.roundToInt())
                }
                .size(
                    with(density) { viewport.contentWidth.toDp() },
                    with(density) { viewport.contentHeight.toDp() },
                )
                // 釘住面板的容器：TextureView 與 TouchForwarder 是這裡唯一的兩個子節點，
                // 共用同一個反向旋轉，兩者物理上永遠疊在一起，觸控才能維持縮放-only 的映射。
                .graphicsLayer { rotationZ = viewport.viewRotationDegrees }
        ) {
            MirrorSurface(
                geometry = geometry,
                addSurface = addSurface,
                removeSurface = removeSurface,
                onTextureViewCreated = onTextureViewCreated,
                modifier = Modifier
                    .align(Alignment.Center)
                    // requiredSize 而非 size：旋轉前的佈局框比父層還長，size() 會被父層
                    // constraints 夾住，requiredSize 才忽略父層。
                    .requiredSize(
                        with(density) { viewport.unrotatedWidth.toDp() },
                        with(density) { viewport.unrotatedHeight.toDp() },
                    ),
            )

            // 跟 MirrorSurface 同尺寸、同一個旋轉容器：黑邊上的觸控因此不會抵達，而手勢
            // 一旦在此開始、拖出邊界仍會送達（view 系統的手勢捕獲）。不對稱語意由結構取得。
            TouchForwarder(
                targetDisplayId = targetDisplayId,
                service = service,
                viewport = viewport,
                geometry = geometry,
                isReadOnly = isReadOnly,
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredSize(
                        with(density) { viewport.unrotatedWidth.toDp() },
                        with(density) { viewport.unrotatedHeight.toDp() },
                    ),
            )
        }
    }
}

@Composable
private fun MirrorSurface(
    geometry: DisplayGeometry,
    addSurface: (Surface) -> Unit,
    removeSurface: (Surface) -> Unit,
    onTextureViewCreated: (TextureView) -> Unit,
    modifier: Modifier = Modifier,
) {
    // TextureView 而非 SurfaceView：SurfaceView 的 surface 是獨立硬體圖層，不吃 view 的
    // 旋轉變換；TextureView 走一般繪製路徑，graphicsLayer 的旋轉才會真的套用。
    val currentGeometry by rememberUpdatedState(geometry)
    val listener = remember(addSurface, removeSurface) {
        object : TextureView.SurfaceTextureListener {
            private var surface: Surface? = null

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                // buffer 尺寸恆為虛擬顯示建立時的大小，不隨旋轉改變；必須在包成 Surface
                // 之前設定，否則 producer 拿到的是 view 尺寸的 buffer。
                texture.setDefaultBufferSize(
                    currentGeometry.surfaceWidth,
                    currentGeometry.surfaceHeight,
                )
                runCatching {
                    Surface(texture).also { surface = it; addSurface(it) }
                }.onFailure { Timber.e(it, "addSurface failed") }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(
                    currentGeometry.surfaceWidth,
                    currentGeometry.surfaceHeight,
                )
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                surface?.let { current ->
                    runCatching { removeSurface(current) }
                        .onFailure { Timber.e(it, "removeSurface failed") }
                    current.release()
                }
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    AndroidView(
        // 反向旋轉套在共用的父容器（見呼叫端），這裡不用再轉一次。
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = listener
                onTextureViewCreated(this)
            }
        },
        update = { view ->
            view.surfaceTexture?.setDefaultBufferSize(
                geometry.surfaceWidth,
                geometry.surfaceHeight,
            )
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun TouchForwarder(
    targetDisplayId: Int,
    service: IRelcV2Service,
    viewport: Viewport,
    geometry: DisplayGeometry,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    // 節點座標是 buffer 空間的相對座標（見容器的 -d·90），只有縮放不需要旋轉就能到 buffer
    // 像素；buffer 像素 → VD 目前的邏輯空間還差一個 v 旋轉，見 touchTransform。
    val currentGeometry by rememberUpdatedState(geometry)

    // 一個手勢（DOWN..UP/CANCEL）中途 v 變了，代表手勢開始時算好的座標系已經不是現在這個。
    // 這裡不試著「跳到新座標系」——那會讓使用者的手指瞬間對到另一個位置——直接丟棄手勢剩餘
    // 的事件，讓使用者放開重按。
    var gestureRotation by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier.pointerInteropFilter { event ->
            if (isReadOnly || targetDisplayId == -1) return@pointerInteropFilter false

            val geom = currentGeometry
            val isGestureStart = event.actionMasked == MotionEvent.ACTION_DOWN
            if (isGestureStart) gestureRotation = geom.rotation
            val staleGesture = gestureRotation != null && gestureRotation != geom.rotation

            if (!staleGesture) {
                service.forwardMirrorTouch(event, targetDisplayId, touchTransform(viewport, geom))
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureRotation = null
            }
            true
        }
    )
}

/**
 * view 像素 → VD 目前的邏輯空間：[Viewport.displayPerViewPixel] 縮放到 buffer 像素，
 * 再疊上跟 VD 自己的 rotation（`geometry.rotation`）對應的旋轉——跟舊版
 * `MirrorSurface` 曾經套用過的 `-v·90` 是同一個角度，只是現在用來變換座標而不是視覺。
 */
private fun touchTransform(viewport: Viewport, geometry: DisplayGeometry): Matrix {
    val matrix = Matrix().apply {
        setScale(viewport.displayPerViewPixel, viewport.displayPerViewPixel)
    }
    val quarterTurns = geometry.rotation and 3
    if (quarterTurns != 0) {
        matrix.postConcat(
            quarterTurnMatrix(quarterTurns, geometry.surfaceWidth.toFloat(), geometry.surfaceHeight.toFloat())
        )
    }
    return matrix
}

/**
 * [rotateToLogical] 的 `android.graphics.Matrix` 版本——同一個仿射變換的兩份寫法，
 * 測試釘住 [rotateToLogical] 就等於釘住這裡。
 */
private fun quarterTurnMatrix(quarterTurns: Int, width: Float, height: Float): Matrix {
    val matrix = Matrix()
    when (quarterTurns and 3) {
        1 -> matrix.setValues(floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f))
        2 -> matrix.setValues(floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f))
        3 -> matrix.setValues(floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f))
    }
    return matrix
}

/** 虛擬顯示的 surface 尺寸與當前方向，全部取自公開的 `Display` API。 */
data class DisplayGeometry(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val rotation: Int,
)

@Composable
fun rememberDisplayGeometry(displayId: Int): DisplayGeometry {
    val context = LocalContext.current
    val displayManager = remember(context) { context.getSystemService(DisplayManager::class.java) }
    var geometry by remember(displayId) {
        mutableStateOf(displayManager.readGeometry(displayId))
    }

    DisposableEffect(displayManager, displayId) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id == displayId) geometry = displayManager.readGeometry(displayId)
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return geometry
}

/**
 * MainDisplay（display 0）目前的 rotation，即 ADR-0014 的 d。跟 [rememberDisplayGeometry]
 * 分開一個函式，因為這裡只需要 rotation，讀 surface 尺寸／`getMode()` 對 display 0
 * 沒有意義。
 */
@Composable
fun rememberHostDisplayRotation(): Int {
    val context = LocalContext.current
    val displayManager = remember(context) { context.getSystemService(DisplayManager::class.java) }
    var rotation by remember {
        mutableStateOf(displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0)
    }

    DisposableEffect(displayManager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id == Display.DEFAULT_DISPLAY) {
                    rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: rotation
                }
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return rotation
}

private fun DisplayManager.readGeometry(displayId: Int): DisplayGeometry {
    val display = getDisplay(displayId) ?: return DisplayGeometry(0, 0, 0)
    val rotation = display.rotation

    // getRealSize() 回報的是邏輯尺寸，會隨旋轉交換長寬；由它反推 surface 尺寸。
    val logical = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
    val derivedWidth = if (isQuarterTurn(rotation)) logical.y else logical.x
    val derivedHeight = if (isQuarterTurn(rotation)) logical.x else logical.y

    // getMode() 回報建立時的 surface 尺寸，不隨旋轉改變——非文件保證，
    // 故以反推值為 fallback 並在不一致時示警。
    val mode = display.mode
    if (mode.physicalWidth <= 0 || mode.physicalHeight <= 0) {
        return DisplayGeometry(derivedWidth, derivedHeight, rotation)
    }
    if (mode.physicalWidth != derivedWidth || mode.physicalHeight != derivedHeight) {
        Timber.w(
            "Display %d: getMode() says %dx%d but getRealSize() implies %dx%d",
            displayId, mode.physicalWidth, mode.physicalHeight, derivedWidth, derivedHeight,
        )
    }
    return DisplayGeometry(mode.physicalWidth, mode.physicalHeight, rotation)
}
