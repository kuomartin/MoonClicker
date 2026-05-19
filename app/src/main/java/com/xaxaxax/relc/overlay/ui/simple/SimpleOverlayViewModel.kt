package com.xaxaxax.relc.overlay.ui.simple

import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.getDefaultLayoutParams
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.addComposable
import com.xaxaxax.relc.overlay.ui.bringToFront
import com.xaxaxax.relc.overlay.ui.removeView
import com.xaxaxax.relc.overlay.ui.updateViewLayout
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import com.xaxaxax.relc.script.ScriptState
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.SimpleSwipePayload
import com.xaxaxax.relc.script.simple.SimpleTapPayload
import com.xaxaxax.relc.script.simple.encodeToPayload
import com.xaxaxax.relc.script.simple.parseSwipePayload
import com.xaxaxax.relc.script.simple.parseTapPayload
import com.xaxaxax.relc.update
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.uuid.ExperimentalUuidApi


@HiltViewModel
@OptIn(ExperimentalUuidApi::class)
class SimpleOverlayViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ScriptRepository,
    private val scriptManager: ScriptManager
) : ViewModel() {
    data class UiState(
        val isRunning: Boolean = false,
        val isRecording: Boolean = false,
        val isCollapsed: Boolean = false,
        val isEditing: Boolean = false,
        val config: ScriptConfig.Simple = ScriptConfig.Simple.Empty,
    )

    private var runningConfig: ScriptConfig.Simple? = null
    private val isRecordingFlow = MutableStateFlow(false)
    private val isCollapsed = MutableStateFlow(false)
    private val isEditing = MutableStateFlow(false)
    private val currentConfig = MutableStateFlow(ScriptConfig.Simple.Empty)

    val uiState: StateFlow<UiState> = combine(
        isRecordingFlow, isEditing, isCollapsed, currentConfig, scriptManager.scriptStates
    ) { recording, editing, collapsed, currentConfig, scripts ->
        val state = scripts[currentConfig.id]
        UiState(state == ScriptState.RUNNING, recording, collapsed, editing, currentConfig)
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UiState()
        )


    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun exitEditScreen() {
        isEditing.value = false
        updatePreviewViews()
    }

    fun updateConfig(config: ScriptConfig.Simple) {
        currentConfig.value = config
    }

    fun save() {
        repository.saveScript(uiState.value.config)
        Toast.makeText(context, "已儲存", Toast.LENGTH_SHORT).show()
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun startScript() {
        syncWindowToLine()
        val configToRun = currentConfig.value
        runningConfig = configToRun
        scriptManager.startScript(configToRun)
        updatePreviewViews()
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun stopScript() {
        runningConfig?.let { scriptManager.stopScript(it.id) }
        updatePreviewViews()
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    private fun addScriptLine(line: ParsedSimpleLine) {
        val index = currentConfig.value.steps.size
        currentConfig.value = currentConfig.value.copy(
            steps = currentConfig.value.steps + line
        )
        addLineToWindow(index, line)
        updatePreviewViews()
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun removeScriptLine() {
        val lastIndex = currentConfig.value.steps.lastIndex
        val last = currentConfig.value.steps.lastOrNull() ?: return
        keyOf(lastIndex, last)?.let {
            overlayWindowScope.removeView(it)
        }
        currentConfig.value = currentConfig.value.copy(
            steps = currentConfig.value.steps.dropLast(1)
        )
    }

    fun updateScriptLine(index: Int, transform: (ParsedSimpleLine) -> ParsedSimpleLine) {
        currentConfig.value = currentConfig.value.copy(
            steps = currentConfig.value.steps.update(index, transform)
        )
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun addTap() {
        val x = overlayWindowScope.metrics.widthPixels
        val y = overlayWindowScope.metrics.heightPixels
        val line = ParsedSimpleLine(
            verb = SimpleScriptVerb.TAP,
            repeatCount = 1,
            delayBetweenRepeatsMs = 0,
            delayAfterStepMs = 0,
            payload = SimpleTapPayload(500, x / 2, y / 2).encodeToPayload()
        )
        addScriptLine(line)
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun startRecording() {
        isRecordingFlow.value = true
        updatePreviewViews()
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun stopRecording(payload: SimpleSwipePayload?) {
        if (payload != null) {
            val line = ParsedSimpleLine(
                verb = SimpleScriptVerb.SWIPE,
                repeatCount = 1,
                delayBetweenRepeatsMs = 0,
                delayAfterStepMs = 0,
                payload = payload.encodeToPayload()
            )
            addScriptLine(line)
        }
        isRecordingFlow.value = false
        updatePreviewViews()
        overlayWindowScope.bringToFront(ViewKeyType.Root)
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun startEdit() {
        isEditing.value = true
        updatePreviewViews()
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun setCollapsed(collapsed: Boolean) {
        isCollapsed.value = collapsed
        updatePreviewViews()
    }

    /**
     * Reactively updates all preview views (alpha and touchability)
     * based on current collapsed and running states.
     */
    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    fun updatePreviewViews() {
        val ui = uiState.value
        val visible = !ui.isCollapsed && !ui.isEditing && !ui.isRecording
        val touchable = visible && !ui.isRunning

        currentConfig.value.steps.forEachIndexed { index, line ->
            val key = keyOf(index, line) ?: return@forEachIndexed
            if (!overlayWindowScope.views.containsKey(key)) {
                addLineToWindow(index, line)
            }
            overlayWindowScope.updateViewLayout(key) { params ->
                params.alpha = if (visible) 1.0f else 0.0f
                if (touchable) {
                    params.flags =
                        params.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).inv()
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                params
            }
        }

        // Manage Root Visibility during recording
        overlayWindowScope.updateViewLayout(ViewKeyType.Root) { params ->
            params.alpha = if (ui.isRecording) 0.0f else 1.0f
            if (ui.isRecording) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags =
                    params.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).inv()
            }
            params
        }
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    private fun syncWindowToLine() {
        currentConfig.value.steps.forEachIndexed { index, line ->
            val key = keyOf(index, line) ?: return@forEachIndexed
            val (_, params) = overlayWindowScope.views[key] ?: return@forEachIndexed
            when (line.verb) {
                SimpleScriptVerb.TAP -> {
                    val padding = dpToPx(16)
                    val tap = parseTapPayload(line.payload)
                        .copy(x = params.x + padding, y = params.y + padding)
                    val newLine = line.copy(payload = tap.encodeToPayload())
                    updateScriptLine(index) { newLine }
                }

                SimpleScriptVerb.SWIPE,
                SimpleScriptVerb.SWIPE_RAW -> {
                    val padding = dpToPx(18)
                    val swipe = parseSwipePayload(line.payload)
                    val box = getSwipeBoundingBox(swipe.points)

                    val dx = params.x - (box.left - padding)
                    val dy = params.y - (box.top - padding)
                    val newPoints = swipe.points.map { (x, y) -> x + dx to y + dy }
                    val newLine =
                        line.copy(payload = swipe.copy(points = newPoints).encodeToPayload())
                    updateScriptLine(index) { newLine }
                }

                else -> {}
            }
        }
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    private fun addLineToWindow(index: Int, line: ParsedSimpleLine) {
        when (line.verb) {
            SimpleScriptVerb.TAP -> {
                val padding = dpToPx(16)
                val tap = parseTapPayload(line.payload)
                val key = ViewKeyType.Tap(index)
                val params = getDefaultLayoutParams().apply {
                    x = tap.x - padding
                    y = tap.y - padding
                }
                overlayWindowScope.addComposable(key, params) {
                    var x by remember { mutableFloatStateOf(tap.x.toFloat() - padding) }
                    var y by remember { mutableFloatStateOf(tap.y.toFloat() - padding) }
                    FloatingTap(
                        index = index,
                        onDrag = { dx, dy ->
                            x += dx
                            y += dy
                            overlayWindowScope.updateViewLayout(key) {
                                it.x = x.toInt()
                                it.y = y.toInt()
                                it
                            }
                        })
                }
            }

            SimpleScriptVerb.SWIPE, SimpleScriptVerb.SWIPE_RAW -> {
                val key = ViewKeyType.Swipe(index)
                val padding = dpToPx(18)
                val swipe = parseSwipePayload(line.payload)
                val box = getSwipeBoundingBox(swipe.points)
                val params = getDefaultLayoutParams().apply {
                    width = box.width() + padding * 2
                    height = box.height() + padding * 2
                    x = box.left - padding
                    y = box.top - padding
                }
                overlayWindowScope.addComposable(key, params) {
                    var x by remember { mutableFloatStateOf(box.left.toFloat() - padding) }
                    var y by remember { mutableFloatStateOf(box.top.toFloat() - padding) }
                    FloatingSwipe(
                        index = index,
                        payload = swipe,
                        offsetX = box.left - padding,
                        offsetY = box.top - padding,
                        onPanDrag = { dx, dy ->
                            x += dx
                            y += dy
                            overlayWindowScope.updateViewLayout(key) {
                                it.x = x.toInt()
                                it.y = y.toInt()
                                it
                            }
                        })
                }
            }

            else -> {}
        }
    }

    context(overlayWindowScope: OverlayWindowScope<ViewKeyType>)
    private fun dpToPx(dp: Int) = (dp * overlayWindowScope.metrics.density).toInt()
    private fun keyOf(index: Int, line: ParsedSimpleLine) = when (line.verb) {
        SimpleScriptVerb.TAP -> ViewKeyType.Tap(index)
        SimpleScriptVerb.SWIPE,
        SimpleScriptVerb.SWIPE_RAW -> ViewKeyType.Swipe(index)

        else -> null
    }
}

private fun getSwipeBoundingBox(points: List<Pair<Int, Int>>): android.graphics.Rect {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (p in points) {
        minX = minOf(minX, p.first)
        minY = minOf(minY, p.second)
        maxX = maxOf(maxX, p.first)
        maxY = maxOf(maxY, p.second)
    }
    return android.graphics.Rect(minX, minY, maxX, maxY)
}