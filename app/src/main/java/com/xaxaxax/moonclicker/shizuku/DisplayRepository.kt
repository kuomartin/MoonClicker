package com.xaxaxax.moonclicker.shizuku

import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.MoonClickerDisplayInfo

/**
 * [WorkbenchServer][com.xaxaxax.moonclicker.workbench.WorkbenchServer] 與 [DisplaysViewModel]
 * 都要列顯示器、切鏡像，兩邊拿到 [IMoonClickerService] 的方式不同（前者直接檢查
 * [ShizukuManager.service]，後者用 [ShizukuManager.withService] 等連線）——這裡只包住兩邊共通的
 * 那一小段：呼叫 AIDL 並吞掉例外，不管呼叫端怎麼拿到 service。
 */
fun IMoonClickerService.displayInfoList(): List<MoonClickerDisplayInfo> =
    runCatching { displayInfos.toList() }.getOrDefault(emptyList())

fun IMoonClickerService.toggleDisplayMirror(displayId: Int, enable: Boolean): Boolean =
    runCatching {
        if (enable) acquireDisplayMirror(displayId) else releaseDisplayMirror(displayId)
    }.getOrDefault(false)
