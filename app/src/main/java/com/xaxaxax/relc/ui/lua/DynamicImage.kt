package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage
import java.io.File

class DynamicImage(
    override val id: String,
    override val padding: PaddingValues,
    override val border: Border,
    override val background: Background,
    override val width: Int?,
    override val height: Int?,
    val src: String,
    val color: Color?,
) : DynamicElement("image") {

    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable() {
        val svgFile = File(uiScope.rootPath, src)
        if (svgFile.exists())
            AsyncImage(
                model = svgFile,
                contentDescription = "外部 SVG 圖示",
                modifier = modifier
            )
        else
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = color ?: LocalContentColor.current,
                modifier = modifier
            )
    }
}
