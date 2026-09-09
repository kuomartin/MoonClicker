package com.xaxaxax.relc.overlay.ui.simple

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xaxaxax.relc.overlay.OverlayContentExtension
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.ViewKeyType
import com.xaxaxax.relc.overlay.ui.updateViewLayout
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import javax.inject.Inject

/**
 * Renders the legacy Simple Script recording/editing overlay UI. This is the
 * :app-side counterpart to :overlay's LuaOverlayContentExtension — kept in :app
 * because it depends on the legacy script.simple.* model (see ADR-0006).
 */
class SimpleOverlayContentExtension @Inject constructor(
    private val scriptRepository: ScriptRepository,
    private val scriptManager: ScriptManager,
) : OverlayContentExtension {
    override val contentType: String = "SIMPLE"

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    @Composable
    override fun Content(scriptId: String, onClose: () -> Unit) {
        val origOffset = Offset(100f, 300f)
        val applicationContext = overlayWindowScope.context.applicationContext
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SimpleOverlayViewModel(
                    applicationContext, scriptRepository, scriptManager
                ) as T
            }
        }
        val viewModel = ViewModelProvider(
            overlayWindowScope.overlayOwner, factory
        )[SimpleOverlayViewModel::class.java]

        val offset = remember { mutableStateOf(origOffset) }
        SimpleOverlay(
            viewModel = viewModel,
            onClose = onClose,
            onDrag = { delta ->
                overlayWindowScope.updateViewLayout(ViewKeyType.Root) { params ->
                    offset.value += delta
                    params.x = offset.value.x.toInt()
                    params.y = offset.value.y.toInt()
                    params
                }
            }
        )
    }
}
