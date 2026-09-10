package com.xaxaxax.relc.lua

import android.view.Surface
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.engine.state.EngineStateRepository
import timber.log.Timber

/**
 * 原生 OpenCV 辨識包裝類
 */
internal object LuaNative {

    private var currentService: IRelcV2Service? = null
    private var currentDisplayId: Int = -1
    private var appContext: android.content.Context? = null

    // Shared state between Lua and Kotlin
    val sharedData = androidx.compose.runtime.mutableStateMapOf<String, Any>()

    fun initContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

    init {
        try {
            System.loadLibrary("relc_native")
        } catch (ex: UnsatisfiedLinkError) {
            Timber.e(ex, "Failed to load relc_native")
        }
    }

    fun showNotification(title: String, text: String) {
        Timber.d("LuaNative showNotification: $title - $text")
        // TODO: Implement actual notification using appContext
    }

    fun startIntent(uri: String) {
        Timber.d("LuaNative startIntent: $uri")
        val ctx = appContext ?: return
        try {
            val intent =
                android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start intent from lua")
        }
    }

    fun systemAction(action: String) {
        Timber.d("LuaNative systemAction: $action on display $currentDisplayId")
        val service = currentService ?: return
        val targetDisplay = if (currentDisplayId != -1) currentDisplayId else 0
        when (action) {
            "home" -> service.injectKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_HOME
                ), targetDisplay
            )

            "back" -> {
                service.injectKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_BACK
                    ), targetDisplay
                )
                service.injectKeyEvent(
                    android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_BACK
                    ), targetDisplay
                )
            }

            "recents" -> service.injectKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_APP_SWITCH
                ), targetDisplay
            )
        }
    }

    /**
     * 啟動原生引擎 (Hot Loop)
     * @param service Shizuku 服務，用於注入事件
     * @param displayId 目標虛擬螢幕 ID
     * @param width 螢幕寬度
     * @param height 螢幕高度
     * @param scriptPath Lua 腳本內容
     * @return 供 VirtualDisplay 使用的 Surface
     */
    fun startEngineWithService(
        service: IRelcV2Service,
        displayId: Int,
        width: Int,
        height: Int,
        scriptPath: String
    ): Surface? {
        this.currentService = service
        this.currentDisplayId = displayId
        EngineStateRepository.reset()
        val surface = startEngine(service, displayId, width, height, scriptPath)
        if (surface != null) startTrackingRotation(displayId)
        return surface
    }

    private external fun startEngine(
        service: IRelcV2Service,
        displayId: Int,
        width: Int,
        height: Int,
        scriptPath: String
    ): Surface?

    /**
     * 停止原生引擎
     */
    external fun stopEngine()

    private external fun nativeSetDisplayRotation(rotation: Int)

    fun stop() {
        stopTrackingRotation()
        stopEngine()
    }

    // --- 虛擬顯示的 rotation ---------------------------------------------------
    // 影格在 surface 空間、injectMotionEvent 用的是邏輯空間，兩者差一個旋轉（見 issue #19）。
    // 這裡把當前 rotation 推進 native，讓 match 的輸出直接是邏輯座標。
    // rotation 由公開的 Display API 取得——比對跑在 app 進程，不需要經過 RelcV2Service。

    private var rotationListener: android.hardware.display.DisplayManager.DisplayListener? = null

    private fun startTrackingRotation(displayId: Int) {
        stopTrackingRotation()
        if (displayId < 0) {
            nativeSetDisplayRotation(0)
            return
        }
        val dm = appContext?.getSystemService(android.hardware.display.DisplayManager::class.java)
        if (dm == null) {
            Timber.w("No DisplayManager; match results will not be rotation-corrected")
            return
        }
        nativeSetDisplayRotation(dm.getDisplay(displayId)?.rotation ?: 0)

        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id != displayId) return
                nativeSetDisplayRotation(dm.getDisplay(displayId)?.rotation ?: 0)
            }
        }
        dm.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        rotationListener = listener
    }

    private fun stopTrackingRotation() {
        val listener = rotationListener ?: return
        rotationListener = null
        appContext?.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.unregisterDisplayListener(listener)
    }

    /**
     * 檢查原生引擎是否正在運行
     */
    external fun isEngineRunning(): Boolean

    /**
     * 引擎狀態事件 (被 C++ 引擎呼叫)，轉發給 [EngineStateRepository]。
     * @param type 對應 [com.xaxaxax.relc.engine.state.EngineEventType]
     */
    fun onEngineEvent(type: Int, payload: String) {
        Timber.d("LuaNative onEngineEvent: type=$type, payload=$payload")
        EngineStateRepository.onEvent(type, payload)
    }

    /**
     * 更新共享數據 (被 C++ 引擎呼叫)
     */
    fun setSharedData(key: String, value: Any?) {
//        Timber.v("LuaNative setSharedData: $key = $value")
        if (value == null) {
            sharedData.remove(key)
        } else {
            sharedData[key] = value
        }
    }
}
