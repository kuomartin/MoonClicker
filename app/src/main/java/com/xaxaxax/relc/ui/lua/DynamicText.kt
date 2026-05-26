package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class DynamicText(
    override val id: String,
    override val x: Any?,
    override val y: Any?,
    override val padding: PaddingValues,
    override val border: Border,
    override val background: Background,
    override val width: Any?,
    override val height: Any?,
    val text: String,
    val size: Int,
    val color: Color,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight
) : DynamicElement("text") {
    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable(modifier: Modifier) {
        val resolvedText = if (text.startsWith("$")) {
            uiScope.sharedData[text.substring(1)]?.toString() ?: text
        } else text

        Text(
            text = resolvedText,
            fontSize = size.sp,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            modifier = modifier.applyModifier()
        )
    }
}
