package com.xaxaxax.moonclicker.script

import com.xaxaxax.moonclicker.core.DisplayConfig

/**
 * 腳本要跑在哪裡。
 *
 * 只有 `MoonClickerService` 自己建立的虛擬顯示拿得到影格，所以**實體螢幕上沒有畫面辨識**——
 * 在那裡呼叫 `vision.*` 會得到明確的 Lua 錯誤。選擇器要把這件事講出來。
 */
sealed interface ScriptTarget {
    /** 本機螢幕（display 0）。只能做輸入注入。 */
    data object PhysicalDisplay : ScriptTarget

    /** 已經存在的虛擬顯示。 */
    data class ExistingVirtual(val displayId: Int) : ScriptTarget

    /**
     * 開一個新的虛擬顯示（若已有同尺寸的就沿用）。
     * 腳本 `script.json` 裡的 `display` 就是解析成這個。
     *
     * 存的是尺寸而不是 displayId：id 每次重建都會變，存下來的必然過期。
     */
    data class NewVirtual(val config: DisplayConfig) : ScriptTarget

    val hasVision: Boolean get() = this !is PhysicalDisplay
}
