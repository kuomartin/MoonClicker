package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

class DynamicBox(
    override val id: String,
    override val padding: PaddingValues = PaddingValues(0.dp),
    override val border: Border = Border(),
    override val background: Background = Background(),
    override val width: Int? = null,
    override val height: Int? = null,
) : DynamicElement("row"), DynamicContainer by DynamicContainerImpl() {

    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable() {
        Box(
            modifier = modifier,
        ) {
            this@DynamicBox.forEach { it.GetComposable() }
        }
    }
}
