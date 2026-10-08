package com.xaxaxax.moonclicker.shizuku

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * App 在前景時持有一個租約：使用者在 App 內操作時服務已經在跑，第一個動作不必等 bind，
 * 頁面切換也不會觸發停止。已授權時由 [ShizukuManager] 看到租約後啟動服務。
 *
 * 掛在 `ProcessLifecycleOwner` 上：它的 ON_START/ON_STOP 是「任何一個 Activity 可見」的開關，
 * 不會因為 Activity 之間切換（例如進出全螢幕）而抖動。
 */
class UserServiceForegroundLease(private val leases: UserServiceLeases) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) = leases.acquire()

    override fun onStop(owner: LifecycleOwner) = leases.release()
}
