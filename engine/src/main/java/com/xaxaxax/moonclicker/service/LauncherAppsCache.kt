package com.xaxaxax.moonclicker.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.os.Build
import android.os.Bundle
import android.os.IRemoteCallback
import android.os.Process
import dev.rikka.tools.refine.Refine
import timber.log.Timber

/**
 * launcher app 清單的暖快取。查詢動作本身有感（scan 全部套件 + 逐一 loadLabel），所以
 * [getLauncherApps] 只讀快取，真正重掃由建構時跑一次，加上 API 35+ 的套件變動回呼
 * （[registerPackageMonitorCallback]）與 [refreshLauncherApps]（手動刷新按鈕）維持。
 */
internal class LauncherAppsCache(
    private val context: Context,
    private val packageManager: PackageManager,
) {
    @Volatile
    private var launcherAppsCache: List<String> = emptyList()

    init {
        rebuildLauncherAppsCache()

        // registerPackageMonitorCallback 是 API 35（VANILLA_ICE_CREAM）才有的 @hide 方法
        // （已查證＋實機驗證，見 hidden-api-contract）；35 以下沒有這條路，快取只能靠上面
        // 這次啟動時的查詢跟 refreshLauncherApps()（手動刷新按鈕）維持。不 unregister——
        // 跟這個 service 本身一樣，活到進程死亡為止。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                val callback = object : IRemoteCallback.Stub() {
                    override fun sendResult(data: Bundle?) {
                        rebuildLauncherAppsCache()
                    }
                }
                // UserHandle.getIdentifier()/getUserId(int) 都是 @SystemApi/@TestApi，不在
                // 公開 SDK 的編譯期 classpath 上；uid / 100000 是 AOSP 內部固定的換算公式
                // （UserHandle.PER_USER_RANGE），不值得為一個 int 另外開一支 hidden-api stub。
                val userId = Process.myUid() / 100000
                Refine.unsafeCast<PackageManagerHidden>(context.packageManager)
                    .registerPackageMonitorCallback(callback, userId)
            } catch (t: Throwable) {
                Timber.e(t, "registerPackageMonitorCallback failed")
            }
        }
    }

    fun getLauncherApps(): List<String> = launcherAppsCache

    fun refreshLauncherApps(): List<String> = rebuildLauncherAppsCache()

    private fun rebuildLauncherAppsCache(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = packageManager.queryIntentActivities(intent, 0).map {
            "${it.activityInfo.packageName}|${it.loadLabel(packageManager)}"
        }
        launcherAppsCache = result
        return result
    }
}
