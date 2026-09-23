package com.xaxaxax.moonclicker.service

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import timber.log.Timber

/**
 * 每個 displayId 背後的原生 GLES distributor：建立、附加/移除消費者 surface、追蹤 rotation、
 * 銷毀。native 呼叫本體因為 JNI 是 name-based 綁定（`Java_..._MoonClickerService_nativeXxx`），
 * 只能留在 [com.xaxaxax.moonclicker.MoonClickerService] 上，這裡用函式參考接進來。
 *
 * `register`／`registerUntracked` 對應兩種呼叫端：[VirtualDisplayLifecycle] 建立的
 * `VirtualDisplay` 有自己的 `Display.rotation` 可以追蹤；[DisplayMirroring] 的 legacy
 * SurfaceControl 鏡像不是一個 `VirtualDisplay`，沒有 rotation 可追。
 */
internal class GlesDistributor(
    private val displayManager: DisplayManager,
    private val create: (Int, Int) -> Long,
    private val getSurface: (Long) -> Surface?,
    private val addSurface: (Long, Surface) -> Int,
    private val removeSurface: (Long, Int) -> Unit,
    private val setRotation: (Long, Int) -> Unit,
    private val destroyNative: (Long) -> Unit,
) {
    private val distributorStore = mutableMapOf<Int, Long>() // displayId -> nativePtr
    private val rotationListeners = mutableMapOf<Int, DisplayManager.DisplayListener>()

    fun createDistributor(width: Int, height: Int): Long = create(width, height)

    fun getDistributorSurface(nativePtr: Long): Surface? = getSurface(nativePtr)

    fun destroyDistributor(nativePtr: Long) = destroyNative(nativePtr)

    /** 登記一個帶 rotation 追蹤的 distributor（來源是某個 [VirtualDisplayLifecycle] 管的 VD）。 */
    fun register(displayId: Int, nativePtr: Long) {
        distributorStore[displayId] = nativePtr
        startRotationTracking(displayId, nativePtr)
    }

    /** 登記一個沒有 rotation 可追的 distributor（legacy SurfaceControl 鏡像）。 */
    fun registerUntracked(displayId: Int, nativePtr: Long) {
        distributorStore[displayId] = nativePtr
    }

    /** 移除登記，回傳被移除的 nativePtr（呼叫端負責 `destroyDistributor`）。 */
    fun unregister(displayId: Int): Long? {
        stopRotationTracking(displayId)
        return distributorStore.remove(displayId)
    }

    fun ptrFor(displayId: Int): Long? = distributorStore[displayId]

    fun attachSurface(displayId: Int, surface: Surface): Int {
        val ptr = ptrFor(displayId) ?: return -1
        return addSurface(ptr, surface)
    }

    fun detachSurface(displayId: Int, handle: Int): Boolean {
        val ptr = ptrFor(displayId) ?: return false
        removeSurface(ptr, handle)
        return true
    }

    private fun startRotationTracking(displayId: Int, nativePtr: Long) {
        setRotation(nativePtr, displayManager.getDisplay(displayId)?.rotation ?: 0)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id != displayId) return
                setRotation(nativePtr, displayManager.getDisplay(displayId)?.rotation ?: 0)
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        rotationListeners[displayId] = listener
    }

    private fun stopRotationTracking(displayId: Int) {
        val listener = rotationListeners.remove(displayId) ?: return
        displayManager.unregisterDisplayListener(listener)
    }
}
