package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class DynamicRow(
    override val id: String,
    override val x: Any?,
    override val y: Any?,
    override val padding: PaddingValues,
    override val border: Border,
    override val background: Background,
    override val width: Any?,
    override val height: Any?,
    val verticalAlignment: Alignment.Vertical,
    val horizontalArrangement: Arrangement.Horizontal,
) : DynamicElement("row"), DynamicContainer by DynamicContainerImpl() {
    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable(modifier: Modifier) {
        Row(
            modifier = modifier.applyModifier(),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment
        ) {
            this@DynamicRow.forEach { it.GetComposable(Modifier) }
        }
    }
}
