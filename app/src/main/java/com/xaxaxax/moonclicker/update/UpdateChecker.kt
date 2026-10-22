package com.xaxaxax.moonclicker.update

import android.content.Context
import android.os.Build
import com.xaxaxax.moonclicker.BuildConfig
import com.xaxaxax.moonclicker.core.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours

/** 上一次成功檢查的結果；檢查失敗不會改變它。 */
sealed interface CheckResult {
    data object NeverChecked : CheckResult
    data class UpToDate(val checkedAtMs: Long) : CheckResult
    data class Available(val update: AvailableUpdate, val checkedAtMs: Long) : CheckResult
}

/** 單次檢查的結果，給手動檢查決定跳對話框還是 toast。 */
sealed interface CheckOutcome {
    data object UpToDate : CheckOutcome
    data class Available(val update: AvailableUpdate) : CheckOutcome
    data class Failed(val reason: String) : CheckOutcome
}

/**
 * 檢查 GitHub Releases 上有沒有比目前安裝更新的版本。手動檢查一律查；自動檢查一天最多一次。
 *
 * 找到的新版會存下來：一天只查一次，App 重啟後小紅點不能因此消失。
 */
@Singleton
class UpdateChecker internal constructor(
    private val releases: GitHubReleases,
    private val appSettings: AppSettings,
    private val store: UpdateStore,
    private val installedVersionName: String,
    /** 依偏好排序，見 [toUpdateFor]。 */
    private val abis: List<String>,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        appSettings: AppSettings,
    ) : this(
        releases = GitHubReleases(),
        appSettings = appSettings,
        store = PrefsUpdateStore(context),
        installedVersionName = BuildConfig.VERSION_NAME,
        abis = Build.SUPPORTED_ABIS.toList(),
        now = System::currentTimeMillis,
    )

    private val installed = Version.fromVersionName(installedVersionName)
    private val mutex = Mutex()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _lastResult = MutableStateFlow(storedResult())
    val lastResult: StateFlow<CheckResult> = _lastResult.asStateFlow()

    private val _hasPendingUpdate = MutableStateFlow(false)

    /** 有新版且沒被略過：設定的導覽項目顯示小紅點。 */
    val hasPendingUpdate: StateFlow<Boolean> = _hasPendingUpdate.asStateFlow()

    init {
        refreshPending()
    }

    /** 不看間隔，也不看略過的版本。 */
    suspend fun check(): CheckOutcome = mutex.withLock {
        _isChecking.value = true
        try {
            val update = releases.newest(appSettings.includePrereleases.value)?.toUpdateFor(installed, abis)
            store.lastCheckedAtMs = now()
            store.lastAvailable = update
            _lastResult.value = storedResult()
            refreshPending()
            update?.let { CheckOutcome.Available(it) } ?: CheckOutcome.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Update check failed")
            CheckOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            _isChecking.value = false
        }
    }

    /** App 進前景時呼叫。距上次成功檢查不到 [CHECK_INTERVAL] 就不查。 */
    suspend fun checkIfDue() {
        if (!appSettings.autoCheckUpdates.value || isRecent()) return
        check()
    }

    /** 上次成功檢查在 [CHECK_INTERVAL] 之內。 */
    fun isRecent(): Boolean = now() - store.lastCheckedAtMs < CHECK_INTERVAL.inWholeMilliseconds

    /** 小紅點不再為這個版本亮；更新的版本照樣亮。 */
    fun skip(version: Version) {
        store.skippedVersion = version.toString()
        refreshPending()
    }

    /** 切換 prerelease 後，舊的結果不再代表新的範圍。 */
    fun invalidate() {
        store.lastCheckedAtMs = 0L
        store.lastAvailable = null
        _lastResult.value = CheckResult.NeverChecked
        refreshPending()
    }

    /** 已裝上的版本不算新版，即使紀錄是在舊版 App 裡存的。 */
    private fun storedResult(): CheckResult {
        val checkedAt = store.lastCheckedAtMs
        if (checkedAt == 0L) return CheckResult.NeverChecked
        val available = store.lastAvailable?.takeIf { installed == null || it.version > installed }
        return available?.let { CheckResult.Available(it, checkedAt) } ?: CheckResult.UpToDate(checkedAt)
    }

    private fun refreshPending() {
        val available = (_lastResult.value as? CheckResult.Available)?.update
        _hasPendingUpdate.value = available != null && available.version.toString() != store.skippedVersion
    }

    private companion object {
        val CHECK_INTERVAL = 24.hours
    }
}
