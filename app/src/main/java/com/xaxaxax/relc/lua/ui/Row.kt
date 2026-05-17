package com.xaxaxax.relc.lua.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class Row(
    override val id: String,
    override val padding: Padding,
    override val bolder: Bolder,
    override val background: Background,
    override val width: Int?,
    override val height: Int?,
    override val clickable: Boolean,
    val alignment: Alignment.Vertical,
    val space: Int,
    val content: DynamicElement?,
) : DynamicElement() {
    @Composable
    override fun GetComposable(onElementClick: (String) -> Unit) {
        Row(
            modifier = buildModifier(onElementClick),
            horizontalArrangement = Arrangement.spacedBy(space.dp),
            verticalAlignment = alignment
        ) {
            content?.GetComposable(onElementClick)
        }
    }
}
