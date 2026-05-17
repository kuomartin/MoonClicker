package com.xaxaxax.relc.lua.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class Column(
    override val id: String,
    override val padding: Padding,
    override val bolder: Bolder,
    override val background: Background,
    override val width: Int?,
    override val height: Int?,
    override val clickable: Boolean,
    val alignment: Alignment.Horizontal,
    val space: Int,
    val content: DynamicElement?,
) : DynamicElement() {
    @Composable
    override fun GetComposable(onElementClick: (String) -> Unit) {
        Column(
            modifier = buildModifier(onElementClick),
            verticalArrangement = Arrangement.spacedBy(space.dp),
            horizontalAlignment = alignment
        ) {
            content?.GetComposable(onElementClick)
        }
    }
}
