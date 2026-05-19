package com.xaxaxax.relc.overlay.ui.simple

sealed interface ViewKeyType {
    data class Tap(val index: Int) : ViewKeyType
    data class Swipe(val index: Int) : ViewKeyType

    object Root : ViewKeyType
    object Recording : ViewKeyType
    data class LuaRoot(val id: String) : ViewKeyType
    data class Spec(val id: String) : ViewKeyType
}
