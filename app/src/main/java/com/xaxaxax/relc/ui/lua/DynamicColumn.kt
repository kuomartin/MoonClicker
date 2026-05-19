package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class DynamicColumn(
    override val id: String,
    override val padding: PaddingValues,
    override val border: Border,
    override val background: Background,
    override val width: Int?,
    override val height: Int?,
    val horizontalAlignment: Alignment.Horizontal,
    val verticalArrangement: Arrangement.Vertical,
) : DynamicElement("column"), DynamicContainer by DynamicContainerImpl() {
    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable(modifier: Modifier) {
        Column(
            modifier = modifier.applyModifier(),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment
        ) {
            this@DynamicColumn.forEach { it.GetComposable(Modifier) }
        }
    }
}
