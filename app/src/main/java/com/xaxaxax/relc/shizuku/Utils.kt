package com.xaxaxax.relc.shizuku

import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.core.DisplayConfig

fun IRelcV2Service.createVirtualDisplay(config: DisplayConfig) = createVirtualDisplay(
    config.name, config.width, config.height, config.densityDpi, config.flags
)