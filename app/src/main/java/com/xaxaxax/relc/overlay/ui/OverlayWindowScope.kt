package com.xaxaxax.relc.overlay.ui

import android.content.Context
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.xaxaxax.relc.overlay.OverlayCompositionOwner
import com.xaxaxax.relc.overlay.installOverlayCompositionOwners

interface OverlayWindowScope<K> {
    val manager: WindowManager
    val context: Context
    val overlayOwner: OverlayCompositionOwner
    val views: MutableMap<K, Pair<View, WindowManager.LayoutParams>>

    val metrics: DisplayMetrics
}

fun <K> OverlayWindowScope<K>.addView(key: K, view: View, params: WindowManager.LayoutParams) {
    view.apply {
        installOverlayCompositionOwners(overlayOwner)
    }
    views.remove(key)?.also { (view, _) ->
        manager.removeView(view)
    }
    views[key] = view to params
    manager.addView(view, params)
}

inline fun <K> OverlayWindowScope<K>.addComposable(
    key: K,
    params: WindowManager.LayoutParams,
    crossinline content: @Composable () -> Unit
) {
    val view = ComposeView(this.context).apply {
        setContent { content() }
    }
    addView(key, view, params)
}


fun <K> OverlayWindowScope<K>.updateViewLayout(
    key: K,
    transform: (origParams: WindowManager.LayoutParams) -> WindowManager.LayoutParams
) {
    val (view, params) = views[key] ?: return
    manager.updateViewLayout(view, transform(params))
}

fun <K> OverlayWindowScope<K>.removeView(key: K) {
    views.remove(key)?.also { (view, _) ->
        manager.removeView(view)
    }
}