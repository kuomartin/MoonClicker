package com.xaxaxax.relc.lua.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage
import java.io.File

class Image(
    override val id: String,
    override val padding: Padding,
    override val bolder: Bolder,
    override val background: Background,
    override val width: Int?,
    override val height: Int?,
    override val clickable: Boolean,
    val path: String,
    val color: Color,
) : DynamicElement() {
    @Composable
    override fun GetComposable(onElementClick: (String) -> Unit) {
        val svgFile = File(path)
        if (svgFile.exists())
            AsyncImage(
                model = svgFile,
                contentDescription = "外部 SVG 圖示",
                modifier = buildModifier(onElementClick)
            )
        else
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = color,
                modifier = buildModifier(onElementClick)
            )
    }
}
