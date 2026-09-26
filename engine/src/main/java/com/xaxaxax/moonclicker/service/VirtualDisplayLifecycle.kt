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
import android.view.DisplayHidden
import android.view.DisplayInfo
import android.view.Surface
import com.xaxaxax.moonclicker.MoonClickerService
import dev.rikka.tools.refine.Refine
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
     * 只有拿到 `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`（需要 `ADD_TRUSTED_DISPLAY`）、而且實際
     * 落在預設 group 以外的 VD 才有自己獨立的 display group；否則它跟主螢幕共用
     * `DEFAULT_DISPLAY_GROUP`，對它做的電源操作會波及主螢幕。電源相關的操作都靠這個欄位擋下
     * 那種情況，不能只看「這個 displayId 是不是我建的」。
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

        /** `Display.DEFAULT_DISPLAY_GROUP`，@hide。 */
        private const val DEFAULT_DISPLAY_GROUP = 0
    }

    private val vdStore = mutableMapOf<Int, ManagedDisplay>()
    private val wakeLocks = DisplayGroupWakeLocks({ platformHandles.powerManagerService }, platformHandles.callerPackage)
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
        val ownsDisplayGroup = usedFlags and DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP != 0 &&
                landedOutsideDefaultGroup(vd.display)
        vdStore[displayId] = ManagedDisplay(vd, surfaceWidth = width, surfaceHeight = height, ownsDisplayGroup = ownsDisplayGroup)
        glesDistributor.register(displayId, nativePtr)
        Timber.d("VirtualDisplay created: id=$displayId name=$name ${width}x${height}@$densityDpi (Distributor Active xxx)")

        runCatching {
            val info = DisplayInfo()
            Refine.unsafeCast<DisplayHidden>(vd.display).getDisplayInfo(info)
            info
        }.onSuccess { Timber.d("DisplayInfo = $it") }
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
        // 舊 distributor 上的 surface 跟著消失，不會有人再為它們呼叫 remove；它們撐著的 wake lock
        // 一併放掉，重連的人會再撐起來。
        wakeLocks.dropAll(displayId)
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
        wakeLocks.dropAll(displayId)
        vdStore.remove(displayId)?.display?.release()
        glesDistributor.unregister(displayId)?.let { ptr -> glesDistributor.destroyDistributor(ptr) }
        return true
    }

    /**
     * 只把 display group 關掉，VD 本身留著——[wakeDisplayGroupIfOwned] 的逆操作。
     *
     * 只對真的擁有獨立 display group（[ManagedDisplay.ownsDisplayGroup]）的 VD 生效——
     * 其餘 VD 跟主螢幕共用 `DEFAULT_DISPLAY_GROUP`，硬呼叫下去會把主螢幕也關掉。
     *
     * 帶 displayId 的 `goToSleep` 從 API 34 起才有，更早的版本一律回 false。
     */
    fun sleepVirtualDisplay(displayId: Int): Boolean {
        if (!canSleep(displayId)) {
            Timber.w("sleepVirtualDisplay: display $displayId cannot be put to sleep on its own, refusing")
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

    /** [sleepVirtualDisplay] 會不會接受：UI 據此決定要不要給「關電源」。 */
    fun canSleep(displayId: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ownsDisplayGroup(displayId)

    /**
     * `FLAG_OWN_DISPLAY_GROUP` 的顯示器有自己獨立的 wakefulness 計時器，閒置逾時後該 group 關閉：
     * API 36 起 VD 的 `state` 變 OFF、輸入被丟棄；API 33–35 的 `state` 仍回報 ON，但系統在上面
     * 疊一層黑色 ColorFade，33/34 上注入的觸控被當成遭遮蔽而丟棄。之後的輸入都叫不醒它，
     * 是個死結（issue #6、#121）。預設 group 的 `wakeUp` 叫不到這個 group。
     *
     * 在每個會操作這個顯示器的入口都先喚醒一次（[DisplayGroupWakeLocks.pulse]），讓它沒有機會
     * 卡進那個死結；緊接著送進去的輸入會重設該 group 的計時。有人在看的期間則由
     * [holdDisplayGroupAwake] 撐著不讓它逾時。
     */
    fun wakeDisplayGroupIfOwned(displayId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ownsDisplayGroup(displayId)) wakeLocks.pulse(displayId)
    }

    /**
     * 多一個正在看 [displayId] 畫面的人（掛上一個 surface）：持有綁在它 display group 上的螢幕
     * wake lock，第一個人進來時順便叫醒它。與 [releaseDisplayGroupAwake] 成對呼叫。
     *
     * surface 的主人若死掉而沒呼叫 [releaseDisplayGroupAwake]，wake lock 會持有到顯示器銷毀為止。
     */
    fun holdDisplayGroupAwake(displayId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ownsDisplayGroup(displayId)) wakeLocks.hold(displayId)
    }

    fun releaseDisplayGroupAwake(displayId: Int) {
        wakeLocks.drop(displayId)
    }

    /**
     * 只有真的拿到 `OWN_DISPLAY_GROUP` 的 VD 才能做——其餘顯示器在預設 group，wake lock 會連主螢幕
     * 一起點亮、一起撐著。這也把範圍限在 API 33+：更早的版本不要求這個旗標。
     */
    private fun ownsDisplayGroup(displayId: Int): Boolean =
        displayId != Display.DEFAULT_DISPLAY && vdStore[displayId]?.ownsDisplayGroup == true

    /**
     * 要了 `OWN_DISPLAY_GROUP` 不保證拿到：Android 17 開啟各 display group 分開逾時時，
     * `LogicalDisplayMapper` 依顯示器類型決定 group，虛擬顯示一律歸進預設 group，旗標被蓋掉
     * （Pixel 7a 實測）。所以問它實際落在哪個 group，不從送出的旗標推斷。讀不到就當作沒拿到——
     * 誤判成擁有會讓電源操作波及主螢幕，反過來只是少了喚醒。
     */
    private fun landedOutsideDefaultGroup(display: Display): Boolean = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val info = DisplayInfo()
        Refine.unsafeCast<DisplayHidden>(display).getDisplayInfo(info) &&
                info.displayGroupId != DEFAULT_DISPLAY_GROUP
    } catch (t: Throwable) {
        Timber.w(t, "could not read the display group of display ${display.displayId}")
        false
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
