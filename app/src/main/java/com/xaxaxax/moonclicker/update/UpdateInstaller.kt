package com.xaxaxax.moonclicker.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import com.xaxaxax.moonclicker.script.ScriptSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下載驗證完之後怎麼進入安裝。
 *
 * 使用者正停在設定頁、沒有腳本在跑，就直接請設定頁進入安裝；否則發通知讓使用者自己決定時機。
 * 安裝會讓 App 被換掉重啟，執行中的腳本會被中斷，所以腳本在跑時絕不主動打斷。
 */
@Singleton
class UpdateInstaller internal constructor(
    private val isScriptRunning: () -> Boolean,
    private val notifier: ReadyNotifier,
    private val canRequestInstalls: () -> Boolean,
    private val launchInstaller: (downloadId: Long) -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        // 下載完成的 broadcast 可能在冷啟動的行程裡處理，只有設定頁可見時才需要它，不必為此建起來。
        scriptSession: dagger.Lazy<ScriptSession>,
        notifier: UpdateNotifier,
    ) : this(
        isScriptRunning = { scriptSession.get().isAnyRunning.value },
        notifier = notifier,
        canRequestInstalls = { context.packageManager.canRequestPackageInstalls() },
        launchInstaller = { id -> launch(context, id) },
    )

    private val settingsVisible = MutableStateFlow(false)

    private val _installRequested = MutableStateFlow(false)

    /** 設定頁看到 `true` 就進入安裝流程，並呼叫 [consumeInstallRequest]。 */
    val installRequested: StateFlow<Boolean> = _installRequested.asStateFlow()

    fun setSettingsVisible(visible: Boolean) {
        settingsVisible.value = visible
    }

    fun onVerified(version: Version) {
        if (settingsVisible.value && !isScriptRunning()) {
            _installRequested.value = true
        } else {
            notifier.notifyReady(version)
        }
    }

    /** 點了「點此安裝」通知。 */
    fun requestInstall() {
        _installRequested.value = true
    }

    fun consumeInstallRequest() {
        _installRequested.value = false
    }

    /** 使用者是否已允許本 App「安裝不明應用程式」。 */
    fun canInstall(): Boolean = canRequestInstalls()

    fun install(downloadId: Long) {
        notifier.cancelReady()
        launchInstaller(downloadId)
    }

    private companion object {
        fun launch(context: Context, downloadId: Long) {
            val uri = context.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(downloadId) ?: return
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}
