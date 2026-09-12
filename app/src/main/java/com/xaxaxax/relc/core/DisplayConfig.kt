package com.xaxaxax.relc.core

data class DisplayConfig(
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int = 320,
    val flags: Int = 0,
    val managed: Boolean = false
)
