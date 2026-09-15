package com.xaxaxax.relc.shizuku

import com.xaxaxax.relc.core.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 「什麼時候會有 UserService 連線」的單一唯讀入口。
 *
 * 機制（[ShizukuManager]）、政策（`UserServiceAutoStarter`）、持久化（[AppSettings]）刻意分開，
 * 但要拼出完整圖像本來要讀兩個檔案——這裡只彙總，不做任何 side effect。比照 [ShizukuManager.statusFlow]
 * 用 [stateIn] 快取成單一熱資料源，讓不同訂閱者（`UserServiceAutoStarter`、`SettingsViewModel`）
 * 看到的是同一份值，不會各自重算 [combine] 而讀到不一致的組合。
 */
class UserServiceLifecycle(
    shizukuManager: ShizukuManager,
    appSettings: AppSettings,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** 與 App 生命週期等長，不需要取消。dispatcher 只為了測試能控制 [snapshot] 何時更新。 */
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    val snapshot: StateFlow<UserServiceLifecycleSnapshot> = combine(
        shizukuManager.statusFlow,
        appSettings.autoStartUserService,
    ) { connection, autoStartEnabled ->
        UserServiceLifecycleSnapshot(connection, autoStartEnabled)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = UserServiceLifecycleSnapshot(
            connection = shizukuManager.status,
            autoStartEnabled = appSettings.autoStartUserService.value,
        ),
    )
}

data class UserServiceLifecycleSnapshot(
    val connection: ShizukuConnectionStatus,
    val autoStartEnabled: Boolean,
)
