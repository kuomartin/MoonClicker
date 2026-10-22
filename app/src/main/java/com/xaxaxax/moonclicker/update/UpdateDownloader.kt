package com.xaxaxax.moonclicker.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import com.xaxaxax.moonclicker.BuildConfig
import com.xaxaxax.moonclicker.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

sealed interface DownloadState {
    data object None : DownloadState
    /** [total] 為 -1 表示伺服器還沒給長度。 */
    data class Running(val bytes: Long, val total: Long) : DownloadState
    data object Verifying : DownloadState
    data class Ready(val version: Version, val downloadId: Long) : DownloadState
    /** [pageUrl] 是 release 頁，讓使用者手動下載。 */
    data class Failed(val reason: DownloadFailure, val pageUrl: String) : DownloadState
}

enum class DownloadFailure { DOWNLOAD_FAILED, CORRUPTED }

sealed interface DownloadStatus {
    data class InProgress(val bytes: Long, val total: Long) : DownloadStatus
    data object Successful : DownloadStatus
    data object Failed : DownloadStatus
}

/** [DownloadManager] 用到的部分，測試時換掉。 */
interface DownloadBackend {
    fun enqueue(url: String, fileName: String, title: String): Long
    /** 下載已被移除（使用者從通知取消、刪了檔案）時回傳 `null`。 */
    fun query(id: Long): DownloadStatus?
    fun remove(id: Long)
    /** 檔案已不存在時回傳 `null`。 */
    fun open(id: Long): InputStream?
}

/**
 * 以 [DownloadManager] 把 APK 下載到 `Download/`，完成後比對 GitHub 給的 sha256。
 *
 * 下載中的系統通知由 [DownloadManager] 顯示，完成後就消失；完成通知改由 [UpdateInstaller] 發，
 * 安裝因此只有一條路，一定經過雜湊驗證。下載紀錄存在 [UpdateStore]，App 重啟後以 ID 還原狀態。
 * 已下載的檔案不會被刪，只有下載中取消、下載失敗或雜湊不符才會移除。
 */
@Singleton
class UpdateDownloader internal constructor(
    private val backend: DownloadBackend,
    private val store: UpdateStore,
    installedVersionName: String,
    private val title: (Version) -> String,
    private val onVerified: (Version) -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        installer: UpdateInstaller,
    ) : this(
        backend = SystemDownloadBackend(context),
        store = PrefsUpdateStore(context),
        installedVersionName = BuildConfig.VERSION_NAME,
        title = { context.getString(R.string.update_download_title, it.toString()) },
        onVerified = installer::onVerified,
    )

    private val mutex = Mutex()

    private val _state = MutableStateFlow<DownloadState>(DownloadState.None)

    /** 由 [refresh] 跟上 [DownloadManager] 的實際狀態；建構時還沒查過，一律是 [DownloadState.None]。 */
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    init {
        // 已經裝上這個版本（或更新的）：紀錄沒用了，檔案留在 Download/。
        val installed = Version.fromVersionName(installedVersionName)
        val record = store.download
        if (record != null && installed != null && record.version <= installed) store.download = null
    }

    /** 同一版本已在下載或已下載完就不重來。 */
    suspend fun start(update: AvailableUpdate) = mutex.withLock {
        val apk = update.apk ?: return@withLock
        withContext(Dispatchers.IO) {
            val existing = store.download
            if (existing != null) {
                if (existing.version == update.version) {
                    refreshLocked()
                    if (_state.value !is DownloadState.None && _state.value !is DownloadState.Failed) return@withContext
                } else if (!existing.verified) {
                    backend.remove(existing.id)
                }
            }
            val id = backend.enqueue(apk.downloadUrl, apk.name, title(update.version))
            store.download = DownloadRecord(id, update.version, apk.name, apk.sha256, update.pageUrl)
            _state.value = DownloadState.Running(0, apk.size.takeIf { it > 0 } ?: -1)
        }
    }

    suspend fun cancel() = mutex.withLock {
        withContext(Dispatchers.IO) {
            store.download?.let { backend.remove(it.id) }
            store.download = null
            _state.value = DownloadState.None
        }
    }

    suspend fun refresh(): DownloadState = mutex.withLock {
        withContext(Dispatchers.IO) { refreshLocked() }
        _state.value
    }

    /** 下載中時每 [PROGRESS_INTERVAL] 更新一次進度，離開下載中就結束。呼叫端在畫面不可見時取消。 */
    suspend fun trackProgress() {
        while (refresh() is DownloadState.Running) delay(PROGRESS_INTERVAL)
    }

    /** `ACTION_DOWNLOAD_COMPLETE`。下載取消或失敗時也會收到，交給 [refresh] 判斷。 */
    suspend fun onDownloadComplete(id: Long) {
        if (store.download?.id != id) return
        refresh()
    }

    private fun refreshLocked() {
        val record = store.download
        if (record == null) {
            // 失敗要留著給使用者看，直到下一次下載。
            if (_state.value !is DownloadState.Failed) _state.value = DownloadState.None
            return
        }
        when (val status = backend.query(record.id)) {
            null -> forget()
            is DownloadStatus.InProgress -> _state.value = DownloadState.Running(status.bytes, status.total)
            DownloadStatus.Failed -> fail(record, DownloadFailure.DOWNLOAD_FAILED)
            DownloadStatus.Successful -> if (record.verified) {
                val fileExists = backend.open(record.id)?.also { it.close() } != null
                if (fileExists) _state.value = DownloadState.Ready(record.version, record.id) else forget()
            } else {
                verify(record)
            }
        }
    }

    private fun verify(record: DownloadRecord) {
        _state.value = DownloadState.Verifying
        val actual = try {
            backend.open(record.id)?.use(::sha256Hex)
        } catch (e: IOException) {
            Timber.w(e, "Reading downloaded update failed")
            null
        } ?: return forget()

        // GitHub 從 2025 年才開始提供 digest；沒有就只能相信簽章檢查。
        if (record.sha256 != null && !actual.equals(record.sha256, ignoreCase = true)) {
            Timber.w("Update ${record.version} sha256 mismatch: expected ${record.sha256}, got $actual")
            return fail(record, DownloadFailure.CORRUPTED)
        }
        store.download = record.copy(verified = true)
        _state.value = DownloadState.Ready(record.version, record.id)
        onVerified(record.version)
    }

    private fun fail(record: DownloadRecord, reason: DownloadFailure) {
        backend.remove(record.id)
        store.download = null
        _state.value = DownloadState.Failed(reason, record.pageUrl)
    }

    private fun forget() {
        store.download = null
        _state.value = DownloadState.None
    }

    private companion object {
        val PROGRESS_INTERVAL = 500.milliseconds
    }
}

private fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/**
 * API 29 以上寫進公用的 `Download/` 不需要權限；27–28 需要 `WRITE_EXTERNAL_STORAGE`，由呼叫端先取得。
 */
internal class SystemDownloadBackend(private val context: Context) : DownloadBackend {
    private val manager = context.getSystemService(DownloadManager::class.java)

    override fun enqueue(url: String, fileName: String, title: String): Long =
        manager.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setMimeType(APK_MIME_TYPE)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        )

    override fun query(id: Long): DownloadStatus? =
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            when (cursor.int(DownloadManager.COLUMN_STATUS)) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Successful
                DownloadManager.STATUS_FAILED -> DownloadStatus.Failed
                else -> DownloadStatus.InProgress(
                    bytes = cursor.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    total = cursor.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
            }
        }

    override fun remove(id: Long) {
        manager.remove(id)
    }

    override fun open(id: Long): InputStream? = try {
        manager.getUriForDownloadedFile(id)?.let(context.contentResolver::openInputStream)
    } catch (e: IOException) {
        null
    }

    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
}
