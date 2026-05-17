package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class DynamicText(
    override val id: String,
    override val padding: PaddingValues,
    override val border: Border,
    override val background: Background,
    override val width: Int?,
    override val height: Int?,
    val text: String,
    val size: Int,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight
) : DynamicElement("text") {
    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable() {
        Text(
            text = text,
            fontSize = size.sp,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            modifier = modifier
        )
    }
}
