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
        script: String
    ): Surface? {
        this.currentService = service
        return startEngine(service, width, height, script)
    }

    private external fun startEngine(
        service: IRelcV2Service,
        width: Int,
        height: Int,
        script: String
    ): Surface?

    /**
     * 停止原生引擎
     */
    external fun stopEngine()

    /**
     * 檢查原生引擎是否正在運行
     */
    external fun isEngineRunning(): Boolean
}