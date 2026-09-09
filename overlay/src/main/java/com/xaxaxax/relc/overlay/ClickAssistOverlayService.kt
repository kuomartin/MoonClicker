package com.xaxaxax.relc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.addView
import com.xaxaxax.relc.overlay.ui.removeView
import com.xaxaxax.relc.overlay.ui.ViewKeyType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ClickAssistOverlayService : AccessibilityService(), OverlayWindowScope<ViewKeyType> {

    @Inject
    lateinit var overlayController: OverlayController

    @Inject
    lateinit var contentExtensions: Map<String, @JvmSuppressWildcards OverlayContentExtension>

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
    private val currentContentType = MutableStateFlow<String?>(null)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("AccessibilityService connected")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayOwner.start()
        overlayController.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events for now
    }

    override fun onInterrupt() {
        Timber.d("AccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.d("AccessibilityService unbound")
        overlayController.detach(this)
        hideOverlay()
        overlayOwner.stop()
        return super.onUnbind(intent)
    }

    /** Called by [OverlayController]; [contentType] selects the [OverlayContentExtension] to render. */
    fun showOverlayForScript(id: String, contentType: String) {
        scriptId = id

        // Ensure we clean up any existing views before showing a new one
        hideOverlay()

        currentContentType.value = contentType
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
                val contentType = currentContentType.collectAsState()
                val id = scriptId
                val extension = contentType.value?.let { contentExtensions[it] }
                if (id != null && extension != null) {
                    extension.Content(scriptId = id, onClose = { hideOverlay() })
                }
            }
        }
        addView(ViewKeyType.Root, view, params)
    }
}
