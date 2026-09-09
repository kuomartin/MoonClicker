package com.xaxaxax.relc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.xaxaxax.relc.simplescript.ui.SimpleScriptsRoute
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
object SettingsRoute

// --- 子頁面 (Sub-pages) ---
@Serializable
data class DisplayDetailRoute(val id: String)

@Serializable
data class ScriptDetailRoute(val id: String)

// SimpleScriptEditorRoute/SimpleEventEditorRoute/SimpleConditionEditorRoute/
// SimpleActionEditorRoute live in :simplescript (com.xaxaxax.relc.simplescript.ui)
// alongside SimpleScriptsRoute above.
