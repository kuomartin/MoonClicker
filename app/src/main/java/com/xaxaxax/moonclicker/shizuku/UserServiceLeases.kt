package com.xaxaxax.moonclicker.shizuku

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「現在有人要用 UserService」的計數。持有租約期間服務不會被自動停止（見 [UserServiceAutoStopper]）。
 *
 * 停止前要查服務端有沒有 VD，而查詢與停止之間可能有人取租約建立 VD；[acquisitions] 讓停止方
 * 確認「查詢開始以來沒有任何人取過租約」，[runIfIdleSince] 與 [acquire] 共用同一把鎖，
 * 所以確認與停止之間不會插進新的租約。
 */
class UserServiceLeases {
    private val lock = Any()

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** 累計取過幾次租約，只增不減。 */
    private var acquisitions = 0L

    fun acquire() {
        synchronized(lock) {
            acquisitions++
            _count.value++
        }
    }

    fun release() {
        synchronized(lock) {
            check(_count.value > 0) { "release() without a matching acquire()" }
            _count.value--
        }
    }

    inline fun <R> hold(block: () -> R): R {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** 給 [runIfIdleSince] 用的起點。 */
    fun mark(): Long = synchronized(lock) { acquisitions }

    /**
     * 目前沒有租約、而且自 [mark] 回傳 [since] 以來沒有人取過租約時執行 [action]。
     * [action] 執行期間 [acquire] 會等它結束，所以它不能等待任何需要租約的工作。
     *
     * @return 是否執行了 [action]。
     */
    fun runIfIdleSince(since: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (_count.value != 0 || acquisitions != since) return false
        action()
        true
    }
}
