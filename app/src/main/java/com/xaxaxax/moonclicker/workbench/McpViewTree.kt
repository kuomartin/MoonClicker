package com.xaxaxax.moonclicker.workbench

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把服務端的 view tree JSON 縮成縮排文字給 MCP 用：每個節點一行，原始 JSON 太佔 token。
 * 看不到的節點連同子樹省略；沒有文字、描述、id，也不能操作的節點只留子節點。
 *
 * ```
 * [application] com.example "Example"
 *   Button "OK" id=ok @540,1200 clickable
 * ```
 */
internal fun compactViewTree(json: String): String = buildString {
    for (window in Json.parseToJsonElement(json).jsonArray) {
        val w = window.jsonObject
        append('[').append(w.string("type") ?: "window").append(']')
        w.string("package")?.let { append(' ').append(it) }
        w.string("title")?.let { append(' ').append(quote(it)) }
        append('\n')
        (w["root"] as? JsonObject)?.let { appendNode(it, 1) }
    }
}

private val FLAGS = listOf("clickable", "long_clickable", "scrollable", "editable", "checkable", "checked", "selected", "focused")
private val ACTIONABLE = setOf("clickable", "long_clickable", "scrollable", "editable", "checkable")

private fun StringBuilder.appendNode(node: JsonObject, depth: Int) {
    if (node.flag("visible") == false) return
    val text = node.string("text")?.takeIf { it.isNotEmpty() }
    val desc = node.string("desc")?.takeIf { it.isNotEmpty() }
    val id = node.string("id")?.substringAfter(":id/")
    val flags = FLAGS.filter { node.flag(it) == true }
    val shown = text != null || desc != null || id != null || flags.any { it in ACTIONABLE }
    val childDepth = if (shown) depth + 1 else depth
    if (shown) {
        repeat(depth) { append("  ") }
        append(node.string("class")?.substringAfterLast('.') ?: "View")
        text?.let { append(' ').append(quote(it)) }
        desc?.let { append(" desc=").append(quote(it)) }
        id?.let { append(" id=").append(it) }
        append(" @").append(node.int("cx")).append(',').append(node.int("cy"))
        flags.forEach { append(' ').append(it) }
        if (node.flag("enabled") == false) append(" disabled")
        append('\n')
    }
    (node["children"] as? JsonArray)?.forEach { child -> (child as? JsonObject)?.let { appendNode(it, childDepth) } }
}

private fun quote(s: String) = Json.encodeToString(s)

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0

private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
