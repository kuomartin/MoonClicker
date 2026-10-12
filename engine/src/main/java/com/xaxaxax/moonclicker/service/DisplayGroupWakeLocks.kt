package com.xaxaxax.moonclicker.service

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IPowerManager
import android.os.PowerManager
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * 綁在單一顯示器上的螢幕 wake lock。
 *
 * `acquireWakeLock` 帶 displayId 時，wake lock 只算進那個顯示器所屬的 display group：持有期間
 * 該 group 不會閒置逾時，`ACQUIRE_CAUSES_WAKEUP` 在 acquire 那一刻只喚醒該 group，不點亮主螢幕
 * （API 33 起；API 12 會喚醒所有 group）。對擁有 `OWN_DISPLAY_GROUP` 的虛擬顯示器，這是 API 33
 * 起唯一能單獨叫醒它、又能讓它不逾時的途徑，見 docs/research/vd-display-group-wake-api31-35.md。
 *
 * 只該用在擁有自己 display group 的顯示器上——否則 wake lock 落在預設 group，連主螢幕一起點亮、
 * 一起撐著不關。這個判斷在呼叫端（[VirtualDisplayLifecycle]）。
 */
internal class DisplayGroupWakeLocks(
    private val power: () -> IPowerManager,
    private val callerPackage: String,
) {
    /**
     * 持有一個綁在 [displayId] 的 wake lock（acquire 時順便叫醒），回傳它的 token，交給 [release]
     * 放掉。每個需要顯示器醒著的人各持有一個，不在這裡計數。acquire 失敗回 null。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun acquire(displayId: Int): IBinder? {
        val token = Binder()
        return if (tryAcquire(token, displayId, "MoonClicker:hold")) token else null
    }

    /**
     * 只喚醒一次，不持有：acquire 後立刻 release。之後該 group 照常計時，緊接著送進去的輸入會
     * 重設它的計時。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun pulse(displayId: Int) {
        val token = Binder()
        if (tryAcquire(token, displayId, "MoonClicker:wake")) release(token)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK：沒有別的 level 能讓 group 維持 Awake
    private fun tryAcquire(token: IBinder, displayId: Int, tag: String): Boolean = try {
        power().acquireWakeLock(
            token,
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            tag,
            callerPackage,
            null,
            null,
            displayId,
            null,
        )
        true
    } catch (t: Throwable) {
        Timber.w(t, "acquireWakeLock($tag, displayId=$displayId) failed")
        false
    }

    fun release(token: IBinder) {
        try {
            power().releaseWakeLock(token, 0)
        } catch (t: Throwable) {
            Timber.w(t, "releaseWakeLock failed")
        }
    }
}
