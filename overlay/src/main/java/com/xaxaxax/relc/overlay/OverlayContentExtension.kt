package com.xaxaxax.relc.overlay

import androidx.compose.runtime.Composable
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.ViewKeyType

/**
 * Renders the overlay content for one script "type". :overlay doesn't know about
 * ScriptConfig (an :app-only type), so implementations are registered under a plain
 * string key via Hilt multibinding (@IntoMap @StringKey) — by convention the calling
 * side passes ScriptConfig.ScriptCodeType.name as both the registration key and the
 * contentType argument to OverlayController.showOverlay.
 */
interface OverlayContentExtension {
    val contentType: String

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    @Composable
    fun Content(scriptId: String, onClose: () -> Unit)
}
