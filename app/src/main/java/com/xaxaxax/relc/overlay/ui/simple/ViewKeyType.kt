package com.xaxaxax.relc.overlay.ui.simple

sealed interface ViewKeyType {
    data class Tap(val index: Int) : ViewKeyType
    data class Swipe(val index: Int) : ViewKeyType

    object Root : ViewKeyType
}