package com.xaxaxax.relc.overlay

import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.xaxaxax.relc.engine.LuaEngineControl
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.ViewKeyType
import com.xaxaxax.relc.overlay.ui.addComposable
import com.xaxaxax.relc.overlay.ui.getDefaultLayoutParams
import com.xaxaxax.relc.overlay.ui.removeView
import com.xaxaxax.relc.overlay.ui.updateViewLayout
import com.xaxaxax.relc.ui.lua.DynamicElement
import com.xaxaxax.relc.ui.lua.LuaUiManager
import com.xaxaxax.relc.ui.lua.LuaUiScope
import java.io.File
import javax.inject.Inject

/** Renders Lua-authored floating UI elements (LuaUiManager). */
class LuaOverlayContentExtension @Inject constructor() : OverlayContentExtension {
    override val contentType: String = "LUA"

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    @Composable
    override fun Content(scriptId: String, onClose: () -> Unit) {
        val scope = remember {
            object : LuaUiScope {
                override val rootPath: File = overlayWindowScope.context.filesDir
                override val sharedData: Map<String, Any> = LuaEngineControl.sharedData
                override fun sendUIEvent(id: String, event: String) {
                    LuaEngineControl.sendUIEvent(id, event)
                }
            }
        }

        // Keep track of positions so they don't reset when elements are updated
        val positions = remember { mutableMapOf<String, Offset>() }

        LuaUiManager.instance.elements.forEach { (id, element) ->
            key(id) {
                LuaWindow(
                    id = id,
                    element = element,
                    scope = scope,
                    initialPosition = positions.getOrPut(id) { Offset(100f, 100f) },
                    onPositionChanged = { newPos -> positions[id] = newPos }
                )
            }
        }
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    @Composable
    private fun LuaWindow(
        id: String,
        element: DynamicElement,
        scope: LuaUiScope,
        initialPosition: Offset,
        onPositionChanged: (Offset) -> Unit
    ) {
        val key = ViewKeyType.LuaRoot(id)
        val offset = remember { mutableStateOf(initialPosition) }

        DisposableEffect(id) {
            val params = getDefaultLayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                x = offset.value.x.toInt()
                y = offset.value.y.toInt()
            }
            overlayWindowScope.addComposable(key, params) {
                with(scope) {
                    element.GetComposable(Modifier.pointerInput(id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset.value += dragAmount
                            overlayWindowScope.updateViewLayout(key) {
                                it.x = offset.value.x.toInt()
                                it.y = offset.value.y.toInt()
                                it
                            }
                            onPositionChanged(offset.value)
                        }
                    })
                }
            }
            onDispose {
                overlayWindowScope.removeView(key)
            }
        }
    }
}
