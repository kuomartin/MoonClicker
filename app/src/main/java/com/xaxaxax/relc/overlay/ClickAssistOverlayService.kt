package com.xaxaxax.relc.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xaxaxax.relc.RelcActivity
import com.xaxaxax.relc.script.ScriptCodeType
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import com.xaxaxax.relc.script.ScriptState
import com.xaxaxax.relc.script.simple.ParsedSimpleLine
import com.xaxaxax.relc.script.simple.SIMPLE_SCRIPT_DEFAULT_STEP_LINE
import com.xaxaxax.relc.script.simple.SimpleScriptBodyJson
import com.xaxaxax.relc.script.simple.SimpleScriptCodec
import com.xaxaxax.relc.script.simple.SimpleScriptVerb
import com.xaxaxax.relc.script.simple.SimpleSwipePayload
import com.xaxaxax.relc.script.simple.encodeToLine
import com.xaxaxax.relc.script.simple.encodeToPayload
import com.xaxaxax.relc.script.simple.parseSimpleScriptLine
import com.xaxaxax.relc.script.simple.parseSwipePayload
import com.xaxaxax.relc.script.simple.parseTapPayload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

fun startClickAssistOverlay(context: Context, scriptId: String) {
    val intent = Intent(context, ClickAssistOverlayService::class.java).apply {
        putExtra("SCRIPT_ID", scriptId)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

@AndroidEntryPoint
class ClickAssistOverlayService : LifecycleService() {

    @Inject
    lateinit var scriptManager: ScriptManager

    @Inject
    lateinit var scriptRepository: ScriptRepository

    private lateinit var windowManager: WindowManager
    private val overlayOwner = OverlayCompositionOwner()

    private var controlBarView: ComposeView? = null
    private var controlBarParams: WindowManager.LayoutParams? = null

    private var detailedEditorView: ComposeView? = null
    private var saveDialogView: ComposeView? = null

    private val targetViews = mutableMapOf<Int, ComposeView>()
    private val targetParams = mutableMapOf<Int, WindowManager.LayoutParams>()

    private var recordingView: ComposeView? = null

    private var scriptId: String? = null
    private val _currentScript = MutableStateFlow(SimpleScriptBodyJson())
    private val _isRecording = MutableStateFlow(false)
    private val _isCollapsed = MutableStateFlow(false)
    private val _showSaveDialog = MutableStateFlow(false)
    private val _showDetailedEditor = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayOwner.start()
        createNotificationChannel()

        lifecycleScope.launch {
            combine(_currentScript, _showDetailedEditor) { body, showEditor ->
                if (showEditor) SimpleScriptBodyJson(emptyList()) else body
            }.collectLatest { body ->
                syncTargets(body)
            }
        }

        lifecycleScope.launch {
            _showSaveDialog.collectLatest { show ->
                if (show) {
                    showSaveDialogOverlay()
                } else {
                    hideSaveDialogOverlay()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val id = intent?.getStringExtra("SCRIPT_ID")
        if (id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (scriptId != id) {
            scriptId = id
            loadScript(id)
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        if (controlBarView == null) {
            showControlBar()
        }

        return result
    }

    private fun loadScript(id: String) {
        val config = scriptRepository.getScript(id)
        if (config != null) {
            _currentScript.value =
                SimpleScriptCodec.decodeOrNull(config.code) ?: SimpleScriptBodyJson()
        }
    }

    private fun saveDraft(name: String? = null, showToast: Boolean = false) {
        val id = scriptId ?: return
        val config = scriptRepository.getScript(id) ?: return
        val updated = config.copy(
            name = name ?: config.name,
            code = SimpleScriptCodec.encode(_currentScript.value)
        )
        scriptRepository.saveScript(updated)
        if (showToast) {
            Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showControlBar() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        controlBarParams = params

        val view = ComposeView(this).apply {
            installOverlayCompositionOwners(overlayOwner)
            setContent {
                val hudUi by scriptManager.hudUi.collectAsState()
                val isRunning = hudUi?.scriptId == scriptId && hudUi?.state == ScriptState.RUNNING
                val isRecording by _isRecording.collectAsState()
                val isCollapsed by _isCollapsed.collectAsState()

                EditorControlBar(
                    uiState = EditorControlUiState(
                        isRunning = isRunning,
                        isRecording = isRecording,
                        isCollapsed = isCollapsed
                    ),
                    onStartStopClick = {
                        val currentId = scriptId ?: return@EditorControlBar
                        if (isRunning) {
                            scriptManager.stopScript(currentId)
                        } else {
                            val currentConfig =
                                scriptRepository.getScript(currentId) ?: return@EditorControlBar
                            val updatedConfig = currentConfig.copy(
                                code = SimpleScriptCodec.encode(_currentScript.value)
                            )
                            scriptManager.startScript(updatedConfig)
                        }
                    },
                    onAddTapClick = { addTap() },
                    onRecordSwipeClick = { startRecording() },
                    onRemoveClick = { removeLast() },
                    onSaveClick = { _showSaveDialog.value = true },
                    onCloseClick = { stopSelf() },
                    onToggleCollapse = { _isCollapsed.value = !_isCollapsed.value },
                    onMoreClick = { showDetailedEditor() },
                    onDrag = { dx, dy ->
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        windowManager.updateViewLayout(this, params)
                    }
                )
            }
        }
        controlBarView = view
        windowManager.addView(view, params)
    }

    private fun addTap() {
        val current = _currentScript.value
        val steps = current.steps.toMutableList()
        steps.add(SIMPLE_SCRIPT_DEFAULT_STEP_LINE)
        _currentScript.value = current.copy(steps = steps)
    }

    private fun removeLast() {
        val current = _currentScript.value
        if (current.steps.isEmpty()) return
        val steps = current.steps.toMutableList()
        steps.removeAt(steps.size - 1)
        _currentScript.value = current.copy(steps = steps)
    }

    private fun startRecording() {
        if (recordingView != null) return
        _isRecording.value = true

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = ComposeView(this).apply {
            installOverlayCompositionOwners(overlayOwner)
            setContent {
                RecordingOverlay(
                    onRecordingFinished = { payload ->
                        addSwipe(payload)
                        stopRecording()
                    },
                    onCancel = {
                        stopRecording()
                    }
                )
            }
        }
        recordingView = view
        windowManager.addView(view, params)
    }

    private fun stopRecording() {
        recordingView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        recordingView = null
        _isRecording.value = false
    }

    private fun showDetailedEditor() {
        if (detailedEditorView != null) return
        _showDetailedEditor.value = true

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val view = ComposeView(this).apply {
            installOverlayCompositionOwners(overlayOwner)
            setContent {
                val hudUi by scriptManager.hudUi.collectAsState()
                val isRunning = hudUi?.scriptId == scriptId && hudUi?.state == ScriptState.RUNNING

                // We need to keep track of the config being edited
                var config by remember {
                    mutableStateOf(
                        scriptRepository.getScript(
                            scriptId ?: ""
                        )!!
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                ) {
                    DetailedScriptEditor(
                        config = config,
                        isRunning = isRunning,
                        onNavigateBack = { hideDetailedEditor() },
                        onUpdateConfig = { updater ->
                            val updated = updater(config)
                            config = updated
                            // If it's simple script, update _currentScript too
                            if (updated.type == ScriptCodeType.SIMPLE) {
                                SimpleScriptCodec.decodeOrNull(updated.code)?.let {
                                    _currentScript.value = it
                                }
                            }
                            updated
                        },
                        onSave = {
                            scriptRepository.saveScript(config)
                            Toast.makeText(
                                this@ClickAssistOverlayService,
                                "已儲存",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onPlay = {
                            scriptRepository.saveScript(config)
                            scriptManager.startScript(config)
                        },
                        onStop = {
                            scriptManager.stopScript(config.id)
                        }
                    )
                }
            }
        }
        detailedEditorView = view
        windowManager.addView(view, params)
    }

    private fun hideDetailedEditor() {
        detailedEditorView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        detailedEditorView = null
        _showDetailedEditor.value = false
    }

    private fun showSaveDialogOverlay() {
        if (saveDialogView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val view = ComposeView(this).apply {
            installOverlayCompositionOwners(overlayOwner)
            setContent {
                val config = remember { scriptRepository.getScript(scriptId ?: "") }
                var name by remember { mutableStateOf(config?.name ?: "") }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "儲存腳本",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("名稱") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { _showSaveDialog.value = false }) {
                                    Text("取消")
                                }
                                TextButton(onClick = {
                                    saveDraft(name, showToast = true)
                                    _showSaveDialog.value = false
                                }) {
                                    Text("儲存")
                                }
                            }
                        }
                    }
                }
            }
        }
        saveDialogView = view
        windowManager.addView(view, params)
    }

    private fun hideSaveDialogOverlay() {
        saveDialogView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        saveDialogView = null
    }

    private fun addSwipe(payload: SimpleSwipePayload) {
        val current = _currentScript.value
        val steps = current.steps.toMutableList()
        val line = ParsedSimpleLine(
            verb = SimpleScriptVerb.SWIPE,
            repeatCount = 1,
            delayBetweenRepeatsMs = 0,
            delayAfterStepMs = 0,
            payload = payload.encodeToPayload()
        ).encodeToLine()
        steps.add(line)
        _currentScript.value = current.copy(steps = steps)
    }

    private fun syncTargets(body: SimpleScriptBodyJson) {
        // Remove views that are no longer in the script or changed verb
        val iterator = targetViews.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val index = entry.key
            val line = body.steps.getOrNull(index)
            if (line == null || !isInteractiveStep(line)) {
                windowManager.removeView(entry.value)
                iterator.remove()
                targetParams.remove(index)
            } else {
                val newVerb = parseSimpleScriptLine(line).verb
                val oldVerb = entry.value.tag as? SimpleScriptVerb
                if (oldVerb != newVerb) {
                    windowManager.removeView(entry.value)
                    iterator.remove()
                    targetParams.remove(index)
                }
            }
        }

        // Add or update views
        body.steps.forEachIndexed { index, line ->
            if (isInteractiveStep(line)) {
                val parsed = parseSimpleScriptLine(line)
                if (targetViews.containsKey(index)) {
                    updateTargetWindow(index, parsed)
                } else {
                    addTargetWindow(index, parsed)
                }
            }
        }
    }

    private fun isInteractiveStep(line: String): Boolean = runCatching {
        val verb = parseSimpleScriptLine(line).verb
        verb == SimpleScriptVerb.TAP || verb == SimpleScriptVerb.SWIPE || verb == SimpleScriptVerb.SWIPE_RAW
    }.getOrDefault(false)

    private val density: Float by lazy { resources.displayMetrics.density }
    private fun dpToPx(dp: Int): Int = (dp * density).toInt()

    private fun addTargetWindow(index: Int, parsed: ParsedSimpleLine) {
        val verb = parsed.verb
        val params = if (verb == SimpleScriptVerb.TAP) {
            val tap = parseTapPayload(parsed.payload)
            val padding = dpToPx(16)
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = tap.x - padding
                this.y = tap.y - padding
            }
        } else {
            // Swipe
            val swipe = parseSwipePayload(parsed.payload)
            val rect = getSwipeBoundingBox(swipe.points)
            val padding = dpToPx(18)
            WindowManager.LayoutParams(
                rect.width() + padding * 2,
                rect.height() + padding * 2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = rect.left - padding
                this.y = rect.top - padding
            }
        }
        targetParams[index] = params

        val view = ComposeView(this).apply {
            tag = verb
            installOverlayCompositionOwners(overlayOwner)
            setContent {
                if (verb == SimpleScriptVerb.TAP) {
                    val padding = dpToPx(16)
                    FloatingTarget(
                        index = index,
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            windowManager.updateViewLayout(this, params)
                            updateScriptTap(index, params.x + padding, params.y + padding)
                        }
                    )
                } else {
                    val swipe = parseSwipePayload(parsed.payload)
                    FloatingSwipe(
                        index = index,
                        payload = swipe,
                        offsetX = params.x,
                        offsetY = params.y,
                        onPanDrag = { dx, dy ->
                            panScriptSwipe(index, dx.toInt(), dy.toInt())
                        }
                    )
                }
            }
        }
        targetViews[index] = view
        windowManager.addView(view, params)
    }

    private fun updateTargetWindow(index: Int, parsed: ParsedSimpleLine) {
        val params = targetParams[index] ?: return
        val view = targetViews[index] ?: return
        val verb = parsed.verb

        if (verb == SimpleScriptVerb.TAP) {
            val tap = parseTapPayload(parsed.payload)
            val padding = dpToPx(16)
            if (params.x != tap.x - padding || params.y != tap.y - padding) {
                params.x = tap.x - padding
                params.y = tap.y - padding
                windowManager.updateViewLayout(view, params)
            }
        } else {
            val swipe = parseSwipePayload(parsed.payload)
            val rect = getSwipeBoundingBox(swipe.points)
            val padding = dpToPx(18)
            val newX = rect.left - padding
            val newY = rect.top - padding
            val newW = rect.width() + padding * 2
            val newH = rect.height() + padding * 2

            if (params.x != newX || params.y != newY || params.width != newW || params.height != newH) {
                params.x = newX
                params.y = newY
                params.width = newW
                params.height = newH
                windowManager.updateViewLayout(view, params)
            }
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

    private fun panScriptSwipe(index: Int, dx: Int, dy: Int) {
        val current = _currentScript.value
        val steps = current.steps.toMutableList()
        val line = steps.getOrNull(index) ?: return
        val parsed = try {
            parseSimpleScriptLine(line)
        } catch (e: Exception) {
            null
        } ?: return
        if (parsed.verb == SimpleScriptVerb.SWIPE || parsed.verb == SimpleScriptVerb.SWIPE_RAW) {
            val payload = parseSwipePayload(parsed.payload)
            val newPoints = payload.points.map { (px, py) -> (px + dx) to (py + dy) }
            steps[index] = parsed.copy(payload = payload.copy(points = newPoints).encodeToPayload())
                .encodeToLine()
            _currentScript.value = current.copy(steps = steps)
        }
    }

    private fun updateScriptTap(index: Int, x: Int, y: Int) {
        val current = _currentScript.value
        val steps = current.steps.toMutableList()
        val line = steps.getOrNull(index) ?: return
        val parsed = try {
            parseSimpleScriptLine(line)
        } catch (e: Exception) {
            null
        } ?: return
        if (parsed.verb == SimpleScriptVerb.TAP) {
            val duration = parsed.payload.substringBefore(',')
            steps[index] = parsed.copy(payload = "$duration,$x,$y").encodeToLine()
            _currentScript.value = current.copy(steps = steps)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Click Assistant Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, RelcActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Click Assistant Overlay")
            .setContentText("Overlay editor is active")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        controlBarView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        targetViews.values.forEach {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        stopRecording()
        overlayOwner.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "click_assistant_overlay"
    }
}
