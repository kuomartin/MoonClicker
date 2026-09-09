package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class DynamicBox(
    override val id: String,
    override val x: Any? = null,
    override val y: Any? = null,
    override val padding: PaddingValues = PaddingValues(0.dp),
    override val border: Border = Border(),
    override val background: Background = Background(),
    override val width: Any? = null,
    override val height: Any? = null,
) : DynamicElement("box"), DynamicContainer by DynamicContainerImpl() {

    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable(modifier: Modifier) {
        Box(
            modifier = modifier.applyModifier(),
        ) {
            this@DynamicBox.forEach { it.GetComposable(Modifier) }
        }
    }
}
