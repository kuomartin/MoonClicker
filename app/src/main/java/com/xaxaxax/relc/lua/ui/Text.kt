package com.xaxaxax.relc.lua.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class Text(
    override val id: String,
    override val padding: Padding,
    override val bolder: Bolder,
    override val background: Background,
    override val width: Int?,
    override val height: Int?,
    override val clickable: Boolean,
    val text: String,
    val size: Int,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight
) : DynamicElement() {
    @Composable
    override fun GetComposable(onElementClick: (String) -> Unit) {
        Text(
            text = text,
            fontSize = size.sp,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            modifier = buildModifier(onElementClick)
        )
    }
}
