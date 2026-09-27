package com.xaxaxax.moonclicker.shizuku

import com.xaxaxax.moonclicker.IMoonClickerService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 租約歸零後等多久才考慮停止：App 短暫切到背景又回來時不重啟行程。 */
val USER_SERVICE_IDLE_GRACE = 30.seconds

/**
 * 沒有人要用 UserService 時把它停掉。
 *
 * 「沒有人要用」有兩半：App 端沒有租約（[UserServiceLeases]），服務端沒有 managed VD 也沒有鏡像。
 * 後者只問服務，不從 App 端推斷——停止會銷毀所有 VD，推斷錯了就是使用者的顯示器憑空消失。
 *
 * 只觀察、只停止，從不啟動：啟動是租約的事（見 [ShizukuManager.withService]）。
 */
class UserServiceAutoStopper(
    private val leases: UserServiceLeases,
    serviceFlow: StateFlow<IMoonClickerService?>,
    private val hasActiveDisplays: (IMoonClickerService) -> Boolean,
    private val stop: () -> Unit,
    grace: Duration = USER_SERVICE_IDLE_GRACE,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** 與 App 生命週期等長，不需要取消。dispatcher 只為了測試能控制時間。 */
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    init {
        scope.launch {
            // 租約歸零、服務在線時開始倒數；倒數中有人取租約或服務斷線，collectLatest 就取消這次倒數。
            // 由通知列或 Shizuku 喚醒、沒有 UI 的 App 行程接上既有服務時也走同一條路。
            combine(leases.count, serviceFlow) { count, service -> service.takeIf { count == 0 } }
                .distinctUntilChanged()
                .collectLatest { service ->
                    if (service == null) return@collectLatest
                    delay(grace)
                    tryStop(service)
                }
        }
    }

    private fun tryStop(service: IMoonClickerService) {
        val since = leases.mark()
        val busy = runCatching { hasActiveDisplays(service) }
            .onFailure { Timber.w(it, "Could not query displays; keeping UserService") }
            .getOrDefault(true)
        if (busy) return

        // 查詢期間若有人取過租約（可能剛建立了 VD），這次查詢的結果就不算數。
        val stopped = leases.runIfIdleSince(since, stop)
        if (stopped) Timber.d("UserService idle, stopped")
    }
}
