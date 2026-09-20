package com.xaxaxax.moonclicker.shizuku

import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.core.DisplayConfig

fun IMoonClickerService.createVirtualDisplay(config: DisplayConfig) = createVirtualDisplay(
    config.name, config.width, config.height, config.densityDpi, config.flags
)