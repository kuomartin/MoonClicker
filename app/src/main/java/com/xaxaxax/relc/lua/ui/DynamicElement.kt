package com.xaxaxax.relc.lua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp


data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val paddingValues =
        PaddingValues.Absolute(left.dp, top.dp, right.dp, bottom.dp)
}

data class Bolder(val width: Int, val color: Color)
data class Background(val color: Color, val rounding: Int)

sealed class DynamicElement {
    abstract val id: String
    abstract val padding: Padding
    abstract val bolder: Bolder
    abstract val background: Background

    abstract val width: Int?
    abstract val height: Int?

    abstract val clickable: Boolean

    protected fun buildModifier(onElementClick: (String) -> Unit): Modifier {
        var baseModifier = Modifier
            .padding(padding.paddingValues)
            .border(bolder.width.dp, bolder.color)
            .background(
                color = background.color,
                shape = RoundedCornerShape(background.rounding.dp)
            )
            .let { m ->
                var finalM = width?.let { w -> m.width(w.dp) } ?: m
                height?.let { h -> finalM.height(h.dp) } ?: finalM
            }

        if (clickable) {
            baseModifier = baseModifier.clickable {
                onElementClick(id)
            }
        }
        return baseModifier
    }

    @Composable
    abstract fun GetComposable(onElementClick: (String) -> Unit)
}
