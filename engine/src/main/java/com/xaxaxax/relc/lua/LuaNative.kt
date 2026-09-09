package com.xaxaxax.relc.lua

import android.view.Surface
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.engine.state.EngineStateRepository
import timber.log.Timber

/**
 * 原生 OpenCV 辨識包裝類
 */
object LuaNative {

    private var currentService: IRelcV2Service? = null
    private var currentDisplayId: Int = -1
    var uiSink: LuaUiSink? = null
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
        uiSink?.clear()
        EngineStateRepository.reset()
        return startEngine(service, displayId, width, height, scriptPath)
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

    fun stop() {
        stopEngine()
        uiSink?.clear()
    }

    /**
     * 檢查原生引擎是否正在運行
     */
    external fun isEngineRunning(): Boolean

    /**
     * 發送 UI 事件給原生引擎 (由 Compose 觸發)
     */
    external fun sendUIEvent(elementId: String, eventType: String)

    /**
     * 新增 UI 節點 (被 C++ 引擎呼叫)
     * @param parentId 父節點 ID，若為根節點則傳遞 null 或空字串
     * @param id 節點的唯一 ID
     * @param jsonExp 該節點屬性的 JSON 描述字串
     */
    fun uiAdd(parentId: String?, id: String, jsonExp: String) {
        Timber.d("LuaNative uiAdd: parentId=$parentId, id=$id, jsonExp=$jsonExp")
        uiSink?.add(parentId, id, jsonExp)
    }

    /**
     * 更新 UI 節點 (被 C++ 引擎呼叫)
     * @param id 節點的唯一 ID
     * @param jsonExp 該節點要更新屬性的 JSON 描述字串
     */
    fun uiUpdate(id: String, jsonExp: String) {
        Timber.d("LuaNative uiUpdate: id=$id, jsonExp=$jsonExp")
        uiSink?.update(id, jsonExp)
    }

    /**
     * 移除 UI 節點 (被 C++ 引擎呼叫)
     * @param id 要移除的節點 ID
     */
    fun uiRemove(id: String) {
        Timber.d("LuaNative uiRemove: id=$id")
        uiSink?.remove(id)
    }

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
