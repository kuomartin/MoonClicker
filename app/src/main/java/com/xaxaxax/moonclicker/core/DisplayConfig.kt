package com.xaxaxax.moonclicker.core

data class DisplayConfig(
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int = 320,
    val flags: Int = 0,
    val managed: Boolean = false,
    /** false 時這個顯示器不綁定輸入法，按鍵不會被使用者的輸入法組字；見 IMoonClickerService.setDisplayKeyboardEnabled。 */
    val keyboard: Boolean = true,
)
