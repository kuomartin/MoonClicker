package com.xaxaxax.moonclicker.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics

/** 一個顯示器目前的幾何，從公開的 Display API 讀出來——不需要經過 MoonClickerService。 */
data class DisplayInfo(
    val displayId: Int,
    val name: String = "Display $displayId",
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val isPhysical: Boolean = false,
    val isMirrorActive: Boolean = false,
)

/** 顯示器可能已經消失（被別處銷毀），所以回傳 nullable 而不是丟例外。 */
fun Context.readDisplayInfo(displayId: Int): DisplayInfo? {
    val display = getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    display.getRealMetrics(metrics)
    return DisplayInfo(
        displayId = displayId,
        name = display.name ?: "Display $displayId",
        width = metrics.widthPixels,
        height = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
    )
}
