package com.xaxaxax.relc.lua

import android.view.Surface
import com.xaxaxax.relc.IRelcV2Service
import timber.log.Timber

/**
 * 原生 OpenCV 辨識包裝類
 */
class LuaNative {

    private var currentService: IRelcV2Service? = null

    companion object {
        init {
            try {
                System.loadLibrary("relc_native")
            } catch (ex: UnsatisfiedLinkError) {
                Timber.e(ex, "Failed to load relc_native")
            }
        }
    }

    /**
     * 啟動原生引擎 (Hot Loop)
     * @param service Shizuku 服務，用於注入事件
     * @param width 螢幕寬度
     * @param height 螢幕高度
     * @param script Lua 腳本內容
     * @return 供 VirtualDisplay 使用的 Surface
     */
    fun startEngineWithService(
        service: IRelcV2Service,
        width: Int,
        height: Int,
        scriptPath: String
    ): Surface? {
        this.currentService = service
        return startEngine(service, width, height, scriptPath)
    }

    private external fun startEngine(
        service: IRelcV2Service,
        width: Int,
        height: Int,
        scriptPath: String
    ): Surface?

    /**
     * 停止原生引擎
     */
    external fun stopEngine()

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
        // TODO: 交給 Compose UI Manager 處理
    }

    /**
     * 更新 UI 節點 (被 C++ 引擎呼叫)
     * @param id 節點的唯一 ID
     * @param jsonExp 該節點要更新屬性的 JSON 描述字串
     */
    fun uiUpdate(id: String, jsonExp: String) {
        Timber.d("LuaNative uiUpdate: id=$id, jsonExp=$jsonExp")
        // TODO: 交給 Compose UI Manager 處理
    }

    /**
     * 移除 UI 節點 (被 C++ 引擎呼叫)
     * @param id 要移除的節點 ID
     */
    fun uiRemove(id: String) {
        Timber.d("LuaNative uiRemove: id=$id")
        // TODO: 交給 Compose UI Manager 處理
    }
}
