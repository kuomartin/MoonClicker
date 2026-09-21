package com.xaxaxax.moonclicker.ui.displaydetail

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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.xaxaxax.moonclicker.IMoonClickerService
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * 把鏡像 view 的生命週期跟虛擬顯示串接起來，並攔截觸控事件轉發給虛擬顯示（[forwardMirrorTouch]）。
 *
 * `v` 在 distributor 端被消掉，鏡像也不再手動套 `-d·90` 抵銷系統的視窗旋轉——
 * `FullscreenDisplayActivity` 的視窗跟著 `d` 自然轉（`configChanges` + `ROTATION_ANIMATION_SEAMLESS`
 * 負責轉場不閃，真機驗證過沒有閃動，見 [ADR-0017](../../../../../../docs/adr/0017-vd-rotation-is-cancelled-at-the-distributor.md)）。
 * letterbox 只需要知道內容轉正後的自然尺寸（`v` 決定要不要互換 `surfaceWidth`/`surfaceHeight`），
 * 不用再管 `d`；[touchTransform] 因此也只剩縮放——view 沒有被旋轉過，內容矩形內的相對座標
 * 已經直接是 `injectMotionEvent` 要的 VD 邏輯空間，不需要反轉任何旋轉。
 */
@Composable
fun VirtualDisplayMirror(
    targetDisplayId: Int,
    geometry: DisplayGeometry,
    addSurface: (Surface) -> Unit,
    removeSurface: (Surface) -> Unit,
    service: IMoonClickerService,
    modifier: Modifier = Modifier,
    onTextureViewCreated: (TextureView) -> Unit = {},
) {
    BoxWithConstraints(modifier.background(Color.Black)) {
        // v 已經在 distributor 被消掉，這裡的「自然尺寸」只是把 v 造成的長寬互換算回來，
        // 跟 d 無關——視窗本身已經是 d 轉正後的形狀（BoxWithConstraints 量到的就是它）。
        val correctedWidth: Int
        val correctedHeight: Int
        if (isQuarterTurn(geometry.rotation)) {
            correctedWidth = geometry.surfaceHeight
            correctedHeight = geometry.surfaceWidth
        } else {
            correctedWidth = geometry.surfaceWidth
            correctedHeight = geometry.surfaceHeight
        }
        val viewport = viewportOf(
            surfaceWidth = correctedWidth,
            surfaceHeight = correctedHeight,
            d = 0,
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
        ) {
            MirrorSurface(
                bufferWidth = correctedWidth,
                bufferHeight = correctedHeight,
                addSurface = addSurface,
                removeSurface = removeSurface,
                onTextureViewCreated = onTextureViewCreated,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        with(density) { viewport.contentWidth.toDp() },
                        with(density) { viewport.contentHeight.toDp() },
                    ),
            )

            // 未旋轉、尺寸正好等於內容矩形：黑邊上的觸控因此不會抵達，而手勢一旦在此開始、
            // 拖出邊界仍會送達（view 系統的手勢捕獲）。不對稱語意由結構取得。
            TouchForwarder(
                targetDisplayId = targetDisplayId,
                service = service,
                viewport = viewport,
                geometry = geometry,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun MirrorSurface(
    bufferWidth: Int,
    bufferHeight: Int,
    addSurface: (Surface) -> Unit,
    removeSurface: (Surface) -> Unit,
    onTextureViewCreated: (TextureView) -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberUpdatedState：listener 重建會連帶重建 Surface，為了換一次
    // v 造成的尺寸交換而重接一次虛擬顯示不划算。
    val currentBufferWidth by rememberUpdatedState(bufferWidth)
    val currentBufferHeight by rememberUpdatedState(bufferHeight)
    val listener = remember(addSurface, removeSurface) {
        object : TextureView.SurfaceTextureListener {
            private var surface: Surface? = null

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                // buffer 尺寸是 distributor 轉正後的自然尺寸（v 已消掉），不是 view 尺寸；
                // 必須在包成 Surface 之前設定，否則 producer 拿到的是 view 尺寸的 buffer。
                texture.setDefaultBufferSize(currentBufferWidth, currentBufferHeight)
                runCatching {
                    Surface(texture).also { surface = it; addSurface(it) }
                }.onFailure { Timber.e(it, "addSurface failed") }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(currentBufferWidth, currentBufferHeight)
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
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = listener
                onTextureViewCreated(this)
            }
        },
        update = { view ->
            view.surfaceTexture?.setDefaultBufferSize(currentBufferWidth, currentBufferHeight)
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun TouchForwarder(
    targetDisplayId: Int,
    service: IMoonClickerService,
    viewport: Viewport,
    geometry: DisplayGeometry,
    modifier: Modifier = Modifier,
) {
    // view 沒有被旋轉過，節點座標已經直接是內容矩形的相對座標，touchTransform 只剩縮放。
    val currentGeometry by rememberUpdatedState(geometry)

    // 一個手勢（DOWN..UP/CANCEL）中途 v 變了、或視窗跟著 d 自然 resize，代表手勢開始時算好
    // 的座標系已經不是現在這個。這裡不試著「跳到新座標系」——那會讓使用者的手指瞬間對到另
    // 一個位置——直接丟棄手勢剩餘的事件，讓使用者放開重按。內容矩形尺寸本身就是兩者的
    // 綜合結果，拿它當 key 比分別追蹤 v 跟視窗尺寸更省事、也不會漏掉純視窗 resize 的情況。
    var gestureKey by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }

    Box(
        modifier.pointerInteropFilter { event ->
            if (targetDisplayId == -1) return@pointerInteropFilter false

            val geom = currentGeometry
            val key = Triple(geom.rotation, viewport.contentWidth, viewport.contentHeight)
            val isGestureStart = event.actionMasked == MotionEvent.ACTION_DOWN
            if (isGestureStart) gestureKey = key
            val staleGesture = gestureKey != null && gestureKey != key

            if (!staleGesture) {
                service.forwardMirrorTouch(event, targetDisplayId, touchTransform(viewport))
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureKey = null
            }
            true
        }
    )
}

/**
 * view 像素（內容矩形內的相對座標）→ VD 目前的邏輯空間，即 `injectMotionEvent` 要的座標系。
 *
 * 只剩縮放：`v` 已經在 distributor 消掉、`d` 已經不再手動反轉（ADR-0017），
 * `MirrorSurface` 顯示的就是轉正後的內容，content rect 內的相對座標經過
 * [Viewport.displayPerViewPixel] 縮放就直接是邏輯座標，不需要再疊任何旋轉矩陣。
 */
private fun touchTransform(viewport: Viewport): Matrix = Matrix().apply {
    setScale(viewport.displayPerViewPixel, viewport.displayPerViewPixel)
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
        mutableIntStateOf(displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0)
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
