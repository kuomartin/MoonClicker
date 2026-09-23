package com.xaxaxax.moonclicker.service

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.DisplayHidden
import android.view.Surface
import android.view.SurfaceControlHidden
import androidx.annotation.RequiresApi
import dev.rikka.tools.refine.Refine
import timber.log.Timber

/**
 * 顯示鏡像：把來源螢幕（實體或虛擬）的畫面接到一個新的消費端。兩條路徑對外是同一組
 * `acquire`/`release` API，呼叫端不需要知道走哪條：
 * - API 34+：`createVirtualDisplay(..., displayId, surface)`，產生一個新的 `Display`/`displayId`，
 *   登記進 [VirtualDisplayLifecycle] 的 vdStore（透過 [VirtualDisplayLifecycle.registerManaged]），
 *   讓 resize/destroy/wake 能統一處理。
 * - API < 34：沒有上面那個建構子重載，改用 `SurfaceControl#createDisplay` 直接把 layer stack
 *   接到來源螢幕（比照 scrcpy）。不會產生新的 `Display`/`displayId`，因此不進 vdStore，
 *   另外記在 [legacyMirrorStore]，key 是來源 physicalDisplayId 本身。
 */
internal class DisplayMirroring(
    private val virtualDisplayLifecycle: VirtualDisplayLifecycle,
    private val glesDistributor: GlesDistributor,
    private val displayManager: DisplayManager,
) {
    private val mirrorRefCounts = mutableMapOf<Int, Int>() // physicalDisplayId -> refCount
    private val mirrorDisplayMap = mutableMapOf<Int, Int>() // physicalDisplayId -> mirrorVirtualDisplayId
    private val legacyMirrorStore = mutableMapOf<Int, IBinder>() // physicalDisplayId -> displayToken

    fun internalMirrorDisplayIds(): Set<Int> = mirrorDisplayMap.values.toSet()

    /** displayId 若是某個鏡像的來源，回傳它實際的 distributor 落在哪個 id 上；否則原樣回傳。 */
    fun resolveDistributorId(displayId: Int): Int = mirrorDisplayMap[displayId] ?: displayId

    @Synchronized
    fun acquireDisplayMirror(displayId: Int): Boolean {
        val current = mirrorRefCounts[displayId] ?: 0
        if (current > 0 && mirrorDisplayMap.containsKey(displayId)) {
            mirrorRefCounts[displayId] = current + 1
            Timber.d("acquireDisplayMirror: display $displayId refCount incremented to ${current + 1}")
            return true
        }

        val ok = createMirrorInternal(displayId)
        if (ok) {
            mirrorRefCounts[displayId] = 1
            Timber.d("acquireDisplayMirror: display $displayId mirror created, refCount=1")
        }
        return ok
    }

    @Synchronized
    fun releaseDisplayMirror(displayId: Int): Boolean {
        val current = mirrorRefCounts[displayId] ?: 0
        if (current <= 0) {
            Timber.w("releaseDisplayMirror: display $displayId has refCount <= 0")
            return false
        }
        val newRef = current - 1
        Timber.d("releaseDisplayMirror: display $displayId refCount decremented to $newRef")
        if (newRef == 0) {
            mirrorRefCounts.remove(displayId)
            val mirrorVdId = mirrorDisplayMap.remove(displayId)
            if (mirrorVdId != null) {
                virtualDisplayLifecycle.destroyVirtualDisplay(mirrorVdId)
                Timber.d("releaseDisplayMirror: destroyed mirror VD $mirrorVdId for display $displayId")
            } else if (legacyMirrorStore.containsKey(displayId)) {
                destroyLegacyMirror(displayId)
                Timber.d("releaseDisplayMirror: destroyed legacy (SurfaceControl) mirror for display $displayId")
            }
        } else {
            mirrorRefCounts[displayId] = newRef
        }
        return true
    }

    fun isDisplayMirrorActive(displayId: Int): Boolean {
        if ((mirrorRefCounts[displayId] ?: 0) <= 0) return false
        return mirrorDisplayMap.containsKey(displayId) || legacyMirrorStore.containsKey(displayId)
    }

    private fun createMirrorInternal(displayId: Int): Boolean {
        val sourceDisplay = displayManager.getDisplay(displayId) ?: run {
            Timber.e("createMirrorInternal: source display $displayId not found")
            return false
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        sourceDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val nativePtr = glesDistributor.createDistributor(width, height)
        if (nativePtr == 0L) return false
        val sourceSurface = glesDistributor.getDistributorSurface(nativePtr) ?: run {
            glesDistributor.destroyDistributor(nativePtr)
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            createMirrorViaVirtualDisplay(displayId, width, height, sourceSurface, nativePtr)
        } else {
            createMirrorViaSurfaceControl(displayId, sourceDisplay, width, height, sourceSurface, nativePtr)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createMirrorViaVirtualDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        sourceSurface: Surface,
        nativePtr: Long,
    ): Boolean {
        val vd = try {
            DisplayManagerHidden.createVirtualDisplay("moonclicker-mirror-$displayId", width, height, displayId, sourceSurface)
        } catch (e: Throwable) {
            Timber.e(e, "createMirrorViaVirtualDisplay: reflection on createVirtualDisplay failed")
            null
        }
        if (vd == null) {
            glesDistributor.destroyDistributor(nativePtr)
            return false
        }

        val mirrorVdId = vd.display?.displayId ?: run {
            vd.release()
            glesDistributor.destroyDistributor(nativePtr)
            return false
        }

        // 這支走 AUTO_MIRROR 的舊式 reflection 建構子，沒有帶 OWN_DISPLAY_GROUP，跟主螢幕共用預設 group。
        virtualDisplayLifecycle.registerManaged(mirrorVdId, vd, surfaceWidth = width, surfaceHeight = height, ownsDisplayGroup = false)
        glesDistributor.register(mirrorVdId, nativePtr)
        mirrorDisplayMap[displayId] = mirrorVdId
        Timber.d("createMirrorViaVirtualDisplay: created mirror VD $mirrorVdId for source display $displayId (${width}x${height})")
        return true
    }

    /**
     * API < 34 沒有帶 displayId 的 mirror 建構子重載（見 [createMirrorInternal]），比照
     * scrcpy 改用 `SurfaceControl#createDisplay` 開一個裸顯示層，把它的 layer stack 設成
     * 跟來源螢幕相同——這樣 SurfaceFlinger 端合成的畫面就等同來源螢幕的鏡像。這條路徑不會
     * 產生新的 `Display`/`displayId`，因此不進 [VirtualDisplayLifecycle] 的 vdStore，
     * 另外記在 [legacyMirrorStore]。
     */
    private fun createMirrorViaSurfaceControl(
        displayId: Int,
        sourceDisplay: Display,
        width: Int,
        height: Int,
        sourceSurface: Surface,
        nativePtr: Long,
    ): Boolean {
        val token = try {
            SurfaceControlHidden.createDisplay("moonclicker-mirror-$displayId", false)
        } catch (t: Throwable) {
            Timber.e(t, "createMirrorViaSurfaceControl: createDisplay failed")
            null
        }
        if (token == null) {
            glesDistributor.destroyDistributor(nativePtr)
            return false
        }

        try {
            val layerStack = Refine.unsafeCast<DisplayHidden>(sourceDisplay).layerStack
            SurfaceControlHidden.openTransaction()
            try {
                SurfaceControlHidden.setDisplaySurface(token, sourceSurface)
                SurfaceControlHidden.setDisplayLayerStack(token, layerStack)
                val rect = Rect(0, 0, width, height)
                SurfaceControlHidden.setDisplayProjection(token, Surface.ROTATION_0, rect, rect)
            } finally {
                SurfaceControlHidden.closeTransaction()
            }
        } catch (t: Throwable) {
            Timber.e(t, "createMirrorViaSurfaceControl: transaction failed for display $displayId")
            SurfaceControlHidden.destroyDisplay(token)
            glesDistributor.destroyDistributor(nativePtr)
            return false
        }

        legacyMirrorStore[displayId] = token
        glesDistributor.registerUntracked(displayId, nativePtr)
        Timber.d("createMirrorViaSurfaceControl: created mirror for source display $displayId (${width}x${height})")
        return true
    }

    private fun destroyLegacyMirror(displayId: Int) {
        legacyMirrorStore.remove(displayId)?.let { token ->
            try {
                SurfaceControlHidden.destroyDisplay(token)
            } catch (t: Throwable) {
                Timber.e(t, "destroyLegacyMirror: destroyDisplay failed for display $displayId")
            }
        }
        glesDistributor.unregister(displayId)?.let { ptr -> glesDistributor.destroyDistributor(ptr) }
    }
}
