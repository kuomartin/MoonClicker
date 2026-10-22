package com.xaxaxax.moonclicker.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [DownloadManager] 完成（含取消、失敗）時通知下載者的 App，App 不在前景也收得到。
 * 驗證雜湊可能要一兩秒，以 [goAsync] 撐住行程。
 */
@AndroidEntryPoint
class UpdateDownloadReceiver : BroadcastReceiver() {
    @Inject
    lateinit var downloader: UpdateDownloader

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                downloader.onDownloadComplete(id)
            } finally {
                pending.finish()
            }
        }
    }
}
