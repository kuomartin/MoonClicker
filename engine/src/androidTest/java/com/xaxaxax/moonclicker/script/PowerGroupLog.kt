package com.xaxaxax.moonclicker.script

import android.os.SystemClock

/**
 * 從 logcat 讀 power group 的睡醒轉換。
 *
 * API 36 以前虛擬顯示器的 `Display.state` 不反映它所屬 group 的電源（`VirtualDisplayAdapter` 只看
 * 有沒有 surface），`dumpsys power` 在 13–15 也不列出各 group 的 wakefulness。`PowerGroup` 每次轉換
 * 都寫一行 log，是 API 33 起各版一致的觀察點。見 docs/research/vd-display-group-wake-api31-35.md。
 */
class PowerGroupLog(private val env: Tier1Env) {

    /** [displayId] 所屬的 display group，`dumpsys display` 裡找不到時為 null。 */
    fun groupOf(displayId: Int): Int? =
        Regex("""displayId $displayId\W+displayGroupId (\d+)""")
            .find(env.shell("dumpsys display"))
            ?.groupValues?.get(1)?.toInt()

    /** 現在的裝置時間，給 [lastTransition] 當起點。 */
    fun mark(): Double = System.currentTimeMillis() / 1000.0

    /** [since] 之後 [groupId] 最後一次轉換：true 為醒來，false 為睡著，null 為沒有轉換。 */
    fun lastTransition(groupId: Int, since: Double): Boolean? =
        env.shell("logcat -d -v epoch -s PowerGroup:I").lineSequence()
            .mapNotNull { line ->
                val time = line.trim().substringBefore(' ').toDoubleOrNull() ?: return@mapNotNull null
                if (time < since) return@mapNotNull null
                if (Regex("""groupId=\s*$groupId\b""").find(line) == null) return@mapNotNull null
                when {
                    "Waking up power group" in line -> true
                    "Powering off display group" in line || "Sleeping power group" in line -> false
                    else -> null
                }
            }
            .lastOrNull()

    /** 做完 [action] 後，等 [groupId] 轉到 [awake]。 */
    fun await(groupId: Int, awake: Boolean, timeoutMs: Long = 5_000, action: () -> Unit): Boolean {
        val since = mark()
        action()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (lastTransition(groupId, since) == awake) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            Thread.sleep(200)
        }
    }

    /** 失敗訊息用：最近的 `PowerGroup` log。 */
    fun recent(): String = env.logcat("PowerGroup", lines = 2_000)
}
