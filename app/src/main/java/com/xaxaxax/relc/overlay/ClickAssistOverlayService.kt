package com.xaxaxax.relc.overlay

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xaxaxax.relc.RelcActivity
import com.xaxaxax.relc.lua.LuaNative
import com.xaxaxax.relc.overlay.ui.OverlayWindowScope
import com.xaxaxax.relc.overlay.ui.addView
import com.xaxaxax.relc.overlay.ui.simple.SimpleOverlay
import com.xaxaxax.relc.overlay.ui.simple.ViewKeyType
import com.xaxaxax.relc.overlay.ui.updateViewLayout
import com.xaxaxax.relc.script.ScriptConfig
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import com.xaxaxax.relc.ui.lua.LuaUiManagerView
import dagger.hilt.android.AndroidEntryPoint
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
class ClickAssistOverlayService : LifecycleService(), OverlayWindowScope<ViewKeyType> {

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
    private var currentConfig: MutableState<ScriptConfig> =
        mutableStateOf(ScriptConfig.Simple.Empty)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayOwner.start()
        createNotificationChannel()
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
        }
        lifecycleScope.launch {
            scriptRepository.getScript(id)?.let {
                currentConfig.value = it
            }
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        showControlBar()

        return result
    }

    private fun showControlBar() {
        val origOffset = Offset(100f, 300f)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = origOffset.x.toInt()
            y = origOffset.y.toInt()
        }
        val view = ComposeView(this).apply {
            setContent {
                when (currentConfig.value.type) {
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
                                removeView(this@apply)
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

                    ScriptConfig.ScriptCodeType.LUA -> LuaUiManagerView(
                        manager = LuaNative.uiManager,
                        luaNative = LuaNative
                    )
                }

            }
        }
        addView(ViewKeyType.Root, view, params)
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
            .setSmallIcon(R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
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
