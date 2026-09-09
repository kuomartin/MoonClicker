package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import org.json.JSONObject


data class Border(val width: Int = 0, val color: Color = Color.Unspecified)
data class Background(val color: Color = Color.Unspecified, val rounding: Int = 0)


sealed class DynamicElement(val type: String) {
    abstract val id: String
    abstract val x: Any?
    abstract val y: Any?
    abstract val padding: PaddingValues
    abstract val border: Border
    abstract val background: Background
    abstract val width: Any?
    abstract val height: Any?
    var clickable: Boolean = false

    protected fun resolveInt(value: Any?, sharedData: Map<String, Any>): Int? {
        if (value == null || value == org.json.JSONObject.NULL) return null
        return when (value) {
            is Number -> value.toInt()
            is String -> if (value.startsWith("$")) {
                sharedData[value.substring(1)]?.let {
                    when (it) {
                        is Number -> it.toInt()
                        is String -> it.toDoubleOrNull()?.toInt()
                        else -> null
                    }
                }
            } else value.toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    @Composable
    context(uiScope: LuaUiScope)
    fun Modifier.applyModifier(): Modifier {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val resolvedX = resolveInt(x, uiScope.sharedData)
        val resolvedY = resolveInt(y, uiScope.sharedData)
        val resolvedW = resolveInt(width, uiScope.sharedData)
        val resolvedH = resolveInt(height, uiScope.sharedData)

        return (if (resolvedX != null || resolvedY != null) {
            this.offset(
                x = resolvedX?.let { with(density) { it.toDp() } } ?: 0.dp,
                y = resolvedY?.let { with(density) { it.toDp() } } ?: 0.dp
            )
        } else this)
            .padding(padding)
            .border(border.width.dp, border.color)
            .background(
                color = background.color,
                shape = RoundedCornerShape(background.rounding.dp)
            )
            .let { m ->
                val finalM = resolvedW?.let { w -> with(density) { m.width(w.toDp()) } } ?: m
                resolvedH?.let { h -> with(density) { finalM.height(h.toDp()) } } ?: finalM
            }
            .clickable(enabled = clickable) { uiScope.sendUIEvent(id, "click") }
    }

    @Composable
    context(uiScope: LuaUiScope)
    abstract fun GetComposable(modifier: Modifier)

    companion object {
        fun fromJsonExp(id: String, json: String): DynamicElement {
            val param = ElementParam(json)
            return when (param.type) {
                "row" -> {
                    DynamicRow(
                        id = id,
                        x = param.x,
                        y = param.y,
                        padding = param.padding,
                        border = param.border,
                        background = param.background,
                        width = param.width,
                        height = param.height,
                        verticalAlignment = param.verticalAlignment,
                        horizontalArrangement = param.horizontalArrangement
                    )
                }

                "column" -> {
                    DynamicColumn(
                        id = id,
                        x = param.x,
                        y = param.y,
                        padding = param.padding,
                        border = param.border,
                        background = param.background,
                        width = param.width,
                        height = param.height,
                        horizontalAlignment = param.horizontalAlignment,
                        verticalArrangement = param.verticalArrangement
                    )
                }

                "text" -> {
                    DynamicText(
                        id = id,
                        x = param.x,
                        y = param.y,
                        padding = param.padding,
                        border = param.border,
                        background = param.background,
                        width = param.width,
                        height = param.height,
                        text = param.text,
                        size = param.size,
                        color = param.color ?: Color.Unspecified,
                        fontFamily = param.fontFamily,
                        fontWeight = param.fontWeight
                    )
                }

                "image" -> {
                    DynamicImage(
                        id = id,
                        x = param.x,
                        y = param.y,
                        padding = param.padding,
                        border = param.border,
                        background = param.background,
                        width = param.width,
                        height = param.height,
                        src = param.src ?: error("Image src not specified"),
                        color = param.color
                    )
                }

                "box" -> {
                    DynamicBox(
                        id = id,
                        x = param.x,
                        y = param.y,
                        padding = param.padding,
                        border = param.border,
                        background = param.background,
                        width = param.width,
                        height = param.height
                    )
                }

                "" -> error("Type not specified")
                else -> error("Type '${param.type}' not allow")
            }
        }
    }
}

private class ElementParam(json: String) {
    private val jsonObj = JSONObject(json)
    val type = jsonObj.getString("type").lowercase()
    val padding: PaddingValues by lazy {
        val obj = jsonObj.optJSONObject("padding") ?: return@lazy PaddingValues.Zero
        val horizontal = obj.opt("horizontal") as? Int
        val vertical = obj.opt("vertical") as? Int
        val start = obj.opt("start") as? Int
        val end = obj.opt("end") as? Int
        val top = obj.opt("top") as? Int
        val bottom = obj.opt("bottom") as? Int
        val left = obj.opt("left") as? Int
        val right = obj.opt("right") as? Int
        when {
            vertical != null || horizontal != null ->
                PaddingValues(horizontal = (horizontal ?: 0).dp, vertical = (vertical ?: 0).dp)

            start != null || end != null ->
                PaddingValues(
                    start = (start ?: 0).dp, end = (end ?: 0).dp,
                    top = (top ?: 0).dp, bottom = (bottom ?: 0).dp,
                )

            else ->
                PaddingValues.Absolute(
                    left = (left ?: 0).dp, right = (right ?: 0).dp,
                    top = (top ?: 0).dp, bottom = (bottom ?: 0).dp,
                )
        }
    }

    private fun parseColor(c: Any?): Color? {
        return when (c) {
            is Number -> {
                val intValue = c.toLong().toInt()
                if (c.toLong() in 1..0xFFFFFFL) {
                    Color(intValue or -0x1000000)
                } else {
                    Color(intValue)
                }
            }

            is String -> try {
                Color(c.toColorInt())
            } catch (e: Exception) {
                null
            }

            else -> null
        }
    }

    val border: Border by lazy {
        val obj = jsonObj.optJSONObject("border")
        val width = obj?.opt("width") as? Int
        val colorObj = obj?.opt("color")
        Border(width = width ?: 0, color = parseColor(colorObj) ?: Color.Unspecified)
    }
    val background: Background by lazy {
        val obj = jsonObj.optJSONObject("background")
        val colorObj = obj?.opt("color")
        val rounding = obj?.opt("rounding") as? Int
        Background(color = parseColor(colorObj) ?: Color.Unspecified, rounding = rounding ?: 0)
    }
    val x: Any? by lazy { jsonObj.opt("x") }
    val y: Any? by lazy { jsonObj.opt("y") }
    val width: Any? by lazy { jsonObj.opt("width") }
    val height: Any? by lazy { jsonObj.opt("height") }
    val clickable: Boolean? by lazy { jsonObj.opt("clickable") as? Boolean }
    val verticalAlignment: Alignment.Vertical by lazy {
        when (jsonObj.opt("alignment")) {
            "center" -> Alignment.CenterVertically
            "top" -> Alignment.Top
            "bottom" -> Alignment.Bottom
            else -> Alignment.Top
        }
    }
    val horizontalAlignment: Alignment.Horizontal by lazy {
        when (jsonObj.opt("alignment")) {
            "center" -> Alignment.CenterHorizontally
            "start" -> Alignment.Start
            "end" -> Alignment.End
            "left" -> AbsoluteAlignment.Left
            "right" -> AbsoluteAlignment.Right
            else -> Alignment.Start
        }
    }

    val verticalArrangement: Arrangement.Vertical by lazy {
        when (val s = jsonObj.opt("space")) {
            is Int -> Arrangement.spacedBy(s.dp)
            "top" -> Arrangement.Top
            "bottom" -> Arrangement.Bottom
            "center" -> Arrangement.Center
            else -> Arrangement.Top
        }
    }
    val horizontalArrangement: Arrangement.Horizontal by lazy {
        when (val s = jsonObj.opt("space")) {
            is Int -> Arrangement.spacedBy(s.dp)
            "start" -> Arrangement.Start
            "end" -> Arrangement.End
            "left" -> Arrangement.Absolute.Left
            "right" -> Arrangement.Absolute.Right
            "evenly" -> Arrangement.SpaceEvenly
            "around" -> Arrangement.SpaceAround
            "between" -> Arrangement.SpaceBetween
            else -> Arrangement.Start
        }
    }
    val text: String by lazy { jsonObj.optString("text", "nil") }
    val size: Int by lazy { jsonObj.optInt("size", 14) }
    val fontFamily: FontFamily by lazy {
        when (jsonObj.opt("fontFamily")) {
            "serif" -> FontFamily.Serif
            "cursive" -> FontFamily.Cursive
            "monospace" -> FontFamily.Monospace
            "sanserif" -> FontFamily.SansSerif
            // todo: support font file
            else -> FontFamily.Default
        }
    }
    val fontWeight: FontWeight by lazy {
        when (jsonObj.opt("fontWeight")) {
            "black" -> FontWeight.Black
            "bold" -> FontWeight.Bold
            "extrabold" -> FontWeight.ExtraBold
            "light" -> FontWeight.Light
            "extralight" -> FontWeight.ExtraLight
            "medium" -> FontWeight.Medium
            "semibold" -> FontWeight.SemiBold
            "thin" -> FontWeight.Thin
            else -> FontWeight.Normal
        }
    }
    val src: String? by lazy { jsonObj.opt("src") as? String }
    val color: Color? by lazy {
        parseColor(jsonObj.opt("color"))
    }
}
