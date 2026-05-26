package com.xaxaxax.relc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

enum class TopLevelDestination(
    val route: Any,
    val icon: ImageVector,
    val label: String
) {
    DISPLAYS(DisplaysRoute, Icons.Default.Tv, "Displays"),
    SCRIPTS(ScriptsRoute, Icons.Default.Code, "Scripts"),
    SIMPLE_SCRIPTS(SimpleScriptsRoute, Icons.Default.Code, "Simple V2"),
    SETTINGS(SettingsRoute, Icons.Default.Settings, "Settings")
}


// --- 頂層頁面 (Top-level) ---
@Serializable
object DisplaysRoute

@Serializable
object ScriptsRoute

@Serializable
object SimpleScriptsRoute

@Serializable
object SettingsRoute

// --- 子頁面 (Sub-pages) ---
@Serializable
data class DisplayDetailRoute(val id: String)

@Serializable
data class ScriptDetailRoute(val id: String)

@Serializable
data class SimpleScriptEditorRoute(val scriptId: Long)

@Serializable
data class SimpleEventEditorRoute(val eventIndex: Int)

@Serializable
data class SimpleConditionEditorRoute(val eventIndex: Int, val conditionIndex: Int)

@Serializable
data class SimpleActionEditorRoute(val eventIndex: Int, val actionIndex: Int)
