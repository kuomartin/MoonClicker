package com.xaxaxax.moonclicker.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 檢查與下載的紀錄。不是使用者偏好，不放 `AppSettings`。 */
interface UpdateStore {
    var lastCheckedAtMs: Long
    var skippedVersion: String?
    /** 最近一次成功檢查找到的新版；沒有新版時為 `null`。 */
    var lastAvailable: AvailableUpdate?
    var download: DownloadRecord?
}

/** 一次 `DownloadManager` 下載。[verified] 表示雜湊已比對通過。 */
@Serializable
data class DownloadRecord(
    val id: Long,
    val version: Version,
    val fileName: String,
    val sha256: String?,
    val pageUrl: String,
    val verified: Boolean = false,
)

internal class PrefsUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var lastCheckedAtMs: Long
        get() = prefs.getLong(KEY_LAST_CHECKED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_CHECKED_AT, value) }

    override var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED_VERSION, null)
        set(value) = prefs.edit { putString(KEY_SKIPPED_VERSION, value) }

    override var lastAvailable: AvailableUpdate?
        get() = decode(KEY_LAST_AVAILABLE)
        set(value) = encode(KEY_LAST_AVAILABLE, value)

    override var download: DownloadRecord?
        get() = decode(KEY_DOWNLOAD)
        set(value) = encode(KEY_DOWNLOAD, value)

    /** 格式對不上（舊版 App 存的）就當作沒有。 */
    private inline fun <reified T> decode(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> encode(key: String, value: T?) =
        prefs.edit { putString(key, value?.let { json.encodeToString(it) }) }

    private companion object {
        const val PREFS_NAME = "update_check"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
        const val KEY_SKIPPED_VERSION = "skipped_version"
        const val KEY_LAST_AVAILABLE = "last_available"
        const val KEY_DOWNLOAD = "download"
        val json = Json { ignoreUnknownKeys = true }
    }
}
