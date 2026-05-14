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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xaxaxax.relc.RelcActivity
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
import com.xaxaxax.relc.script.simple.parseTapPayload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    private val targetViews = mutableMapOf<Int, ComposeView>()
    private val targetParams = mutableMapOf<Int, WindowManager.LayoutParams>()

    private var recordingView: ComposeView? = null

    private var scriptId: String? = null
    private val _currentScript = MutableStateFlow(SimpleScriptBodyJson())
    private val _isRecording = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayOwner.start()
        createNotificationChannel()

        lifecycleScope.launch {
            _currentScript.collectLatest { body ->
                syncTargets(body)
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

    private fun saveDraft(showToast: Boolean = false) {
        val id = scriptId ?: return
        val config = scriptRepository.getScript(id) ?: return
        val updated = config.copy(code = SimpleScriptCodec.encode(_currentScript.value))
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

                EditorControlBar(
                    uiState = EditorControlUiState(
                        isRunning = isRunning,
                        isRecording = isRecording
                    ),
                    onStartStopClick = {
                        val currentId = scriptId ?: return@EditorControlBar
                        if (isRunning) {
                            scriptManager.stopScript(currentId)
                        } else {
                            scriptRepository.getScript(currentId)?.let {
                                scriptManager.startScript(it)
                            }
                        }
                    },
                    onAddTapClick = { addTap() },
                    onRecordSwipeClick = { startRecording() },
                    onRemoveClick = { removeLast() },
                    onSaveClick = { saveDraft(true) },
                    onCloseClick = { stopSelf() },
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

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
            val step = body.steps.getOrNull(index)
            if (step == null || !isTapStep(step)) {
                windowManager.removeView(entry.value)
                iterator.remove()
                targetParams.remove(index)
            }
        }

        // Add or update views
        body.steps.forEachIndexed { index, line ->
            if (isTapStep(line)) {
                val tap = parseTapPayload(parseSimpleScriptLine(line).payload)
                if (targetViews.containsKey(index)) {
                    updateTargetWindow(index, tap.x, tap.y)
                } else {
                    addTargetWindow(index, tap.x, tap.y)
                }
            }
        }
    }

    private fun isTapStep(line: String): Boolean = runCatching {
        parseSimpleScriptLine(line).verb == SimpleScriptVerb.TAP
    }.getOrDefault(false)

    private fun addTargetWindow(index: Int, x: Int, y: Int) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - 16
            this.y = y - 16
        }
        targetParams[index] = params

        val view = ComposeView(this).apply {
            installOverlayCompositionOwners(overlayOwner)
            setContent {
                FloatingTarget(
                    index = index,
                    onDrag = { dx, dy ->
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        windowManager.updateViewLayout(this, params)
                        updateScriptTap(index, params.x + 16, params.y + 16)
                    }
                )
            }
        }
        targetViews[index] = view
        windowManager.addView(view, params)
    }

    private fun updateTargetWindow(index: Int, x: Int, y: Int) {
        val params = targetParams[index] ?: return
        if (params.x != x - 16 || params.y != y - 16) {
            params.x = x - 16
            params.y = y - 16
            windowManager.updateViewLayout(targetViews[index], params)
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
