package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage
import java.io.File

class DynamicImage(
    override val id: String,
    override val x: Any?,
    override val y: Any?,
    override val padding: PaddingValues,
    override val border: Border,
    override val background: Background,
    override val width: Any?,
    override val height: Any?,
    val src: String,
    val color: Color?,
) : DynamicElement("image") {

    context(uiScope: LuaUiScope)
    @Composable
    override fun GetComposable(modifier: Modifier) {
        val svgFile = File(uiScope.rootPath, src)
        if (svgFile.exists())
            AsyncImage(
                model = svgFile,
                contentDescription = "外部 SVG 圖示",
                modifier = modifier.applyModifier()
            )
        else
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = color ?: LocalContentColor.current,
                modifier = modifier.applyModifier()
            )
    }
}
