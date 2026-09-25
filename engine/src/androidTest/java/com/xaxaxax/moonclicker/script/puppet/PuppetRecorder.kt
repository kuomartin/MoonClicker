package com.xaxaxax.moonclicker.script.puppet

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** [PuppetActivity] 此刻觀測到的一切，整份一起發佈，讀的人不會拿到不同時間點的欄位組合。 */
internal data class PuppetState(
    /** activity 還沒被 destroy。 */
    val alive: Boolean = false,
    /** puppet 目前 resume 在哪個顯示器上；-1 表示沒有。 */
    val resumedOnDisplay: Int = -1,
    /** content view 的實際尺寸——用來確認它鋪滿了顯示器，以及旋轉有沒有生效。 */
    val contentSize: Pair<Int, Int>? = null,
    /** [PuppetMarker]（旋轉對稱）被畫在 view 座標的哪裡。 */
    val markerRect: Rect? = null,
    /** [PuppetGlyph]（不對稱）被畫在哪裡。 */
    val glyphRect: Rect? = null,
    val touches: List<PuppetRecorder.Touch> = emptyList(),
)

/**
 * [PuppetActivity] 看到什麼，就記在這裡。
 *
 * library 的 androidTest APK 是自我 instrument 的，puppet 與測試程式碼在同一個進程，所以測試
 * 直接讀這個 object，不需要 IPC、第二個 APK 或 UiAutomator。等待一律掛在 [state] 上，由
 * puppet 的每一次更新喚醒，不輪詢。
 */
internal object PuppetRecorder {

    data class Touch(val action: Int, val x: Float, val y: Float, val displayId: Int)

    private val _state = MutableStateFlow(PuppetState())
    val state: StateFlow<PuppetState> = _state.asStateFlow()

    val current: PuppetState get() = _state.value

    fun update(transform: (PuppetState) -> PuppetState) = _state.update(transform)

    /** 保留 [PuppetState.alive]：它描述的是 activity 本身，不是這個測試觀測到的東西。 */
    fun reset() = _state.update { PuppetState(alive = it.alive) }

    fun clearTouches() = _state.update { it.copy(touches = emptyList()) }

    /** @return 第一個滿足 [condition] 的狀態；逾時為 null。 */
    fun await(timeoutMs: Long, condition: (PuppetState) -> Boolean): PuppetState? = runBlocking {
        withTimeoutOrNull(timeoutMs) { state.first(condition) }
    }

    /** 等到 puppet 在 [displayId] 上 resume 並完成第一次 layout。 */
    fun awaitReady(displayId: Int, timeoutMs: Long = 10_000): Boolean =
        await(timeoutMs) { it.resumedOnDisplay == displayId && it.markerRect != null } != null

    fun awaitTouch(timeoutMs: Long = 5_000, predicate: (Touch) -> Boolean): Touch? =
        await(timeoutMs) { it.touches.any(predicate) }?.touches?.first(predicate)
}
