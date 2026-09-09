package com.xaxaxax.relc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.xaxaxax.relc.getDefaultLayoutParams
import com.xaxaxax.relc.engine.LuaEngineControl
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.addComposable
import com.xaxaxax.relc.overlay.ui.addView
import com.xaxaxax.relc.overlay.ui.removeView
import com.xaxaxax.relc.overlay.ui.simple.SimpleOverlay
import com.xaxaxax.relc.overlay.ui.simple.ViewKeyType
import com.xaxaxax.relc.overlay.ui.updateViewLayout
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import com.xaxaxax.relc.ui.lua.DynamicElement
import com.xaxaxax.relc.ui.lua.LuaUiScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ClickAssistOverlayService : AccessibilityService(), OverlayWindowScope<ViewKeyType> {

    @Inject
    lateinit var scriptManager: ScriptManager

    @Inject
    lateinit var scriptRepository: ScriptRepository

    private lateinit var windowManager: WindowManager
    override val manager: WindowManager
        get() = windowManager
    override val context: Context = this
    override val overlayOwner = OverlayCompositionOwner()

    override val views: MutableMap<ViewKeyType, Pair<View, WindowManager.LayoutParams>> =
        mutableMapOf()

    override val metrics: DisplayMetrics by lazy {
        resources.displayMetrics
    }


    private var scriptId: String? = null
    private var currentConfig = MutableStateFlow<ScriptConfig>(ScriptConfig.Simple.Empty)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("AccessibilityService connected")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayOwner.start()
        OverlayServiceProvider.setService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events for now
    }

    override fun onInterrupt() {
        Timber.d("AccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.d("AccessibilityService unbound")
        OverlayServiceProvider.setService(null)
        hideOverlay()
        overlayOwner.stop()
        return super.onUnbind(intent)
    }

    fun showOverlayForScript(id: String) {
        if (scriptId != id) {
            scriptId = id
        }

        // Ensure we clean up any existing views before showing a new one
        hideOverlay()

        // This is a coroutine launched on AccessibilityService's lifecycleScope, which is valid since it is a Service (though AccessibilityService doesn't have a default lifecycleScope, we might need to implement LifecycleOwner or use a custom scope. Wait, AccessibilityService doesn't implement LifecycleOwner. Let me use overlayOwner.lifecycleScope)
        overlayOwner.lifecycleScope.launch {
            scriptRepository.getScript(id)?.let {
                currentConfig.value = it
            }
        }

        showControlBar()
    }

    fun hideOverlay() {
        // A helper to remove all views
        val keys = views.keys.toList()
        for (key in keys) {
            removeView(key)
        }
    }

    private fun showControlBar() {
        val origOffset = Offset(100f, 300f)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = origOffset.x.toInt()
            y = origOffset.y.toInt()
        }
        val view = ComposeView(this).apply {
            setContent {
                val config = currentConfig.collectAsState()
                when (config.value.type) {
                    ScriptConfig.ScriptCodeType.SIMPLE -> {
                        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                @Suppress("UNCHECKED_CAST")
                                return com.xaxaxax.relc.overlay.ui.simple.SimpleOverlayViewModel(
                                    applicationContext, scriptRepository, scriptManager
                                ) as T
                            }
                        }
                        val viewModel = androidx.lifecycle.ViewModelProvider(
                            overlayOwner, factory
                        )[com.xaxaxax.relc.overlay.ui.simple.SimpleOverlayViewModel::class.java]


                        val offset = remember { mutableStateOf(origOffset) }
                        SimpleOverlay(
                            viewModel = viewModel,
                            onClose = {
                                hideOverlay()
                            },
                            onDrag = { delta ->
                                this@ClickAssistOverlayService.updateViewLayout(ViewKeyType.Root) { params ->
                                    offset.value += delta
                                    params.x = offset.value.x.toInt()
                                    params.y = offset.value.y.toInt()
                                    params
                                }
                            }
                        )
                    }

                    ScriptConfig.ScriptCodeType.LUA ->
                        LuaUiOverlay()
                }

            }
        }
        addView(ViewKeyType.Root, view, params)
    }

    @Composable
    private fun LuaUiOverlay() {
        val context = this
        val scope = remember {
            object : LuaUiScope {
                override val rootPath: File = context.filesDir
                override val sharedData: Map<String, Any> = LuaEngineControl.sharedData
                override fun sendUIEvent(id: String, event: String) {
                    LuaEngineControl.sendUIEvent(id, event)
                }
            }
        }

        // Keep track of positions so they don't reset when elements are updated
        val positions = remember { mutableMapOf<String, Offset>() }

        com.xaxaxax.relc.ui.lua.LuaUiManager.instance.elements.forEach { (id, element) ->
            key(id) {
                LuaWindow(
                    id = id,
                    element = element,
                    scope = scope,
                    initialPosition = positions.getOrPut(id) { Offset(100f, 100f) },
                    onPositionChanged = { newPos ->
                        positions[id] = newPos
                    }
                )
            }
        }
    }

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
            addComposable(key, params) {
                with(scope) {
                    element.GetComposable(Modifier.pointerInput(id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset.value += dragAmount
                            updateViewLayout(key) {
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
                removeView(key)
            }
        }
    }
}
