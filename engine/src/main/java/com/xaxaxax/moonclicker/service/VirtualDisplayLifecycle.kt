package com.xaxaxax.moonclicker.service

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManagerHidden
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.PowerManagerHidden
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import com.xaxaxax.moonclicker.MoonClickerService
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * 一個虛擬顯示連同它建立時的尺寸——那是常數，記下來就不必從邏輯尺寸加 rotation 回推
 * （兩次獨立讀取之間畫面轉了，答案會錯得很有自信）。見 [surfaceSize]。
 */
private class ManagedDisplay(
    val display: VirtualDisplay,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    /**
     * 只有拿到 `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`（需要 `ADD_TRUSTED_DISPLAY`）的 VD
     * 才有自己獨立的 display group；沒有的話它跟主螢幕共用 `DEFAULT_DISPLAY_GROUP`，對它做的
     * 電源操作會波及主螢幕。[VirtualDisplayLifecycle.sleepVirtualDisplay] 靠這個欄位擋下那種
     * 情況，不能只看「這個 displayId 是不是我建的」。
     */
    val ownsDisplayGroup: Boolean,
)

/**
 * 虛擬顯示的生命週期：建立、resize、銷毀、休眠，以及誰擁有自己 display group 的判定。
 * [com.xaxaxax.moonclicker.service.DisplayMirroring] 建立的 VD 版鏡像也登記在這裡的
 * [vdStore]（透過 [registerManaged]），讓 resize/destroy/wake 能統一處理，不必另開第二份
 * 「哪些 VD 存在」的真相來源。
 */
internal class VirtualDisplayLifecycle(
    private val context: Context,
    private val platformHandles: PlatformHandles,
    private val glesDistributor: GlesDistributor,
) {
    companion object {
        /** 呼叫端可以要求的旗標，其餘一律由這裡決定。 */
        const val SUPPORTED_FLAGS =
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS

        /** 每個 API level 都給的基本盤。 */
        const val ADD_FLAGS = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT

        /** API 34 起，兩者都依賴顯示器是 trusted。 */
        const val ADD_FLAGS_34 = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
    }

    private val vdStore = mutableMapOf<Int, ManagedDisplay>()
    private val grantedPermissions = ConcurrentHashMap<String, Boolean>()

    fun allDisplayIds(): Set<Int> = vdStore.keys.toSet()

    fun isKnownVirtualDisplay(displayId: Int): Boolean = vdStore.containsKey(displayId)

    fun surfaceSize(displayId: Int): Pair<Int, Int>? =
        vdStore[displayId]?.let { it.surfaceWidth to it.surfaceHeight }

    /** process 即將結束（見 `MoonClickerService.destroy`）：只釋放 VD，不管 distributor——
     * `exitProcess` 緊接在後，native 資源交給進程結束回收，不值得多做一輪清理。 */
    fun releaseAll() {
        vdStore.values.forEach { it.display.release() }
    }

    /** 讓 [DisplayMirroring] 把它自己建立的 VD（VD 版鏡像）登記進同一份 [vdStore]。 */
    fun registerManaged(displayId: Int, vd: VirtualDisplay, surfaceWidth: Int, surfaceHeight: Int, ownsDisplayGroup: Boolean) {
        vdStore[displayId] = ManagedDisplay(vd, surfaceWidth, surfaceHeight, ownsDisplayGroup)
    }

    fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int, flags: Int): Int {
        val baseFlags = (flags and SUPPORTED_FLAGS) or ADD_FLAGS
        val privilegedFlags = baseFlags or privilegedFlags()

        val nativePtr = glesDistributor.createDistributor(width, height)
        if (nativePtr == 0L) return -1
        val sourceSurface = glesDistributor.getDistributorSurface(nativePtr) ?: run {
            glesDistributor.destroyDistributor(nativePtr)
            return -1
        }

        var usedFlags = privilegedFlags
        val vd = createDisplay(name, width, height, densityDpi, sourceSurface, privilegedFlags)
            ?: createDisplay(name, width, height, densityDpi, sourceSurface, baseFlags).also { usedFlags = baseFlags }
            ?: run {
                glesDistributor.destroyDistributor(nativePtr)
                return -1
            }

        val displayId = vd.display?.displayId ?: run {
            vd.release()
            glesDistributor.destroyDistributor(nativePtr)
            return -1
        }
        val ownsDisplayGroup = usedFlags and DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP != 0
        vdStore[displayId] = ManagedDisplay(vd, surfaceWidth = width, surfaceHeight = height, ownsDisplayGroup = ownsDisplayGroup)
        glesDistributor.register(displayId, nativePtr)
        Timber.d("VirtualDisplay created: id=$displayId name=$name ${width}x${height}@$densityDpi (Distributor Active)")
        return displayId
    }

    /**
     * 見 `IMoonClickerService.resizeVirtualDisplay`。舊 distributor 上掛的 sink 不轉移——
     * 低機率換高結構成本（handle 是每個 distributor 各自從 0 起算，轉移需要一張對照表，
     * 而目前 AIDL 沒有回呼機制能把新 handle 通知回呼叫端），呼叫端自己重連即可。
     */
    fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int): Boolean {
        val managed = vdStore[displayId] ?: return false
        val oldPtr = glesDistributor.ptrFor(displayId) ?: return false

        val newPtr = glesDistributor.createDistributor(width, height)
        if (newPtr == 0L) return false
        val newSurface = glesDistributor.getDistributorSurface(newPtr) ?: run {
            glesDistributor.destroyDistributor(newPtr)
            return false
        }

        try {
            managed.display.resize(width, height, densityDpi)
            managed.display.setSurface(newSurface)
        } catch (t: Throwable) {
            Timber.e(t, "resizeVirtualDisplay: resize/setSurface failed for display $displayId")
            glesDistributor.destroyDistributor(newPtr)
            return false
        }

        glesDistributor.unregister(displayId)
        glesDistributor.destroyDistributor(oldPtr)
        vdStore[displayId] = ManagedDisplay(
            managed.display,
            surfaceWidth = width,
            surfaceHeight = height,
            ownsDisplayGroup = managed.ownsDisplayGroup,
        )
        glesDistributor.register(displayId, newPtr)
        Timber.d("resizeVirtualDisplay: display $displayId resized to ${width}x${height}@$densityDpi")
        return true
    }

    fun destroyVirtualDisplay(displayId: Int): Boolean {
        vdStore.remove(displayId)?.display?.release()
        glesDistributor.unregister(displayId)?.let { ptr -> glesDistributor.destroyDistributor(ptr) }
        return true
    }

    /**
     * 只把 display group 關掉，VD 本身留著——[wakeDisplayGroupIfOwned] 的逆操作。
     *
     * 只對真的拿到 `OWN_DISPLAY_GROUP`（[ManagedDisplay.ownsDisplayGroup]）的 VD 生效——
     * 沒有這個旗標的 VD 跟主螢幕共用 `DEFAULT_DISPLAY_GROUP`，硬呼叫下去會把主螢幕也關掉。
     *
     * 帶 displayId 的 `goToSleep` 從 API 34 起才有，更早的版本一律回 false。
     */
    fun sleepVirtualDisplay(displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val managed = vdStore[displayId] ?: return false
        if (!managed.ownsDisplayGroup) {
            Timber.w("sleepVirtualDisplay: display $displayId does not own its display group, refusing")
            return false
        }
        return try {
            platformHandles.powerManagerHidden.goToSleep(
                displayId,
                SystemClock.uptimeMillis(),
                PowerManagerHidden.GO_TO_SLEEP_REASON_APPLICATION,
                0,
            )
            true
        } catch (t: Throwable) {
            Timber.w(t, "goToSleep(displayId=$displayId) failed")
            false
        }
    }

    /**
     * `FLAG_OWN_DISPLAY_GROUP` 的顯示器有自己獨立的 wakefulness 計時器，閒置逾時後
     * `state` 變 OFF——而 OFF 之後 `InputDispatcher` 直接丟棄送進來的輸入事件，
     * 正常的「輸入喚醒 userActivity」路徑因此叫不醒它，是個死結（issue #6）。
     *
     * 在每個會讓使用者看到/操作這個顯示器的入口都主動喚醒一次，讓它沒有機會卡進那個死結。
     * 只對本服務自己建立、確實拿到這個旗標的顯示器做，不動主螢幕或其他一般顯示器。
     *
     * 帶 displayId 的 `wakeUp` 從 API 36 起才有；API 31–35 的 VD 同樣有獨立 display group，
     * 卻沒有 API 能單獨喚醒它，這幾個版本上死結仍可能發生。
     */
    fun wakeDisplayGroupIfOwned(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) return
        if (!vdStore.containsKey(displayId)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        try {
            platformHandles.powerManagerHidden.wakeUp(
                SystemClock.uptimeMillis(),
                PowerManagerHidden.WAKE_REASON_APPLICATION,
                "MoonClicker own-display-group keep-awake",
                displayId,
            )
        } catch (t: Throwable) {
            Timber.d(t, "wakeUp(displayId=$displayId) failed")
        }
    }

    /**
     * API 33+ 的特權旗標，逐項按它自己的前提決定——`DisplayManagerService` 裡是三條獨立的
     * 檢查，不要綁成一包給或一包丟。
     *
     * 綁成一包的代價：有 `ADD_TRUSTED_DISPLAY` 卻沒有 `ADD_ALWAYS_UNLOCKED_DISPLAY` 的機器
     * 會為一個旗標讓整個顯示器退回非 trusted，而少了 `ALWAYS_UNLOCKED`，虛擬顯示在裝置
     * 鎖定或休眠時收不到注入的觸控。API 33 這條界線與 scrcpy 的 `NewDisplayCapture` 一致；
     * 見 docs/virtual-display-pitfalls.md。
     */
    private fun privilegedFlags(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 0

        // 沒有權限檢查，一律給。
        var f = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED

        if (holds(MoonClickerService.ADD_TRUSTED_DISPLAY)) {
            f = f or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP

            // 依賴 OWN_DISPLAY_GROUP（見 javadoc），所以巢狀在這裡，但要的是另一個權限。
            if (holds(MoonClickerService.ADD_ALWAYS_UNLOCKED_DISPLAY)) {
                f = f or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
            }

            // 依賴顯示器是 trusted。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                f = f or ADD_FLAGS_34
            }
        }
        return f
    }

    /**
     * 這個進程有沒有某個權限——直接問，不要用丟例外去試。`checkSelfPermission` 查的是
     * `Process.myUid()`，正是 `DisplayManagerService` 會拿去對的那個身分。
     */
    private fun holds(permission: String): Boolean =
        grantedPermissions.getOrPut(permission) {
            val granted = context.checkSelfPermission(permission) ==
                    PackageManager.PERMISSION_GRANTED
            Timber.d("$permission granted=$granted for uid=${Process.myUid()}")
            granted
        }

    /**
     * 建一個虛擬顯示，失敗回 `null`。
     *
     * 呼叫端用 [privilegedFlags] 試一次、被擋下來再用基本旗標試一次——那些旗標各自還有
     * 權限以外的前提。非 trusted 的顯示器仍然建得起來、收得到影格與觸控，只是行為降級。
     */
    private fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay? = try {
        platformHandles.displayManagerHidden.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
    } catch (e: SecurityException) {
        Timber.w(e, "createVirtualDisplay rejected with flags=0x${flags.toString(16)}")
        null
    }
}
