package com.xaxaxax.moonclicker.shizuku

import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.core.DisplayConfig

/** [viaVirtualDevice] 對應設定的實驗性開關，見 [IMoonClickerService.FLAG_VIRTUAL_DEVICE]。 */
fun IMoonClickerService.createVirtualDisplay(config: DisplayConfig, viaVirtualDevice: Boolean) = createVirtualDisplay(
    config.name, config.width, config.height, config.densityDpi,
    if (viaVirtualDevice) config.flags or IMoonClickerService.FLAG_VIRTUAL_DEVICE else config.flags,
)
