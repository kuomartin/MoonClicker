package com.xaxaxax.relc.ui.displaydetail

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
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
 * 幾何全部來自單一的 [Viewport]（見地圖 #9 / #12）：呈現與觸控讀同一個 instance，
 * 且座標變換只有 [Viewport.displayPerViewPixel] 一個來源。`Viewport` 的輸入取自公開的
 * `Display` API，不需要任何 AIDL。
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
        val viewport = viewportOf(
            surfaceWidth = geometry.surfaceWidth,
            surfaceHeight = geometry.surfaceHeight,
            rotation = geometry.rotation,
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
                geometry = geometry,
                viewport = viewport,
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

            // 觸控節點未旋轉，尺寸正好等於內容矩形：黑邊上的觸控因此不會抵達，而手勢
            // 一旦在此開始、拖出邊界仍會送達（view 系統的手勢捕獲）。不對稱語意由結構取得。
            TouchForwarder(
                targetDisplayId = targetDisplayId,
                service = service,
                viewport = viewport,
                isReadOnly = isReadOnly,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun MirrorSurface(
    geometry: DisplayGeometry,
    viewport: Viewport,
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
        // 影格在 surface 空間、內容躺著，套上反向旋轉才會正立。
        modifier = modifier.graphicsLayer { rotationZ = viewport.viewRotationDegrees },
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
    isReadOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    // 節點座標已是內容矩形內的相對座標，只需縮放——觸控路徑上沒有旋轉，那被畫面側的
    // graphicsLayer 吸收掉了。係數取自 Viewport.displayPerViewPixel，與 toDisplay 同源。
    val transform = remember(viewport.displayPerViewPixel) {
        Matrix().apply {
            setScale(viewport.displayPerViewPixel, viewport.displayPerViewPixel)
        }
    }
    Box(
        modifier.pointerInteropFilter { event ->
            if (isReadOnly || targetDisplayId == -1) {
                false
            } else {
                service.forwardMirrorTouch(event, targetDisplayId, transform)
                true
            }
        }
    )
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
