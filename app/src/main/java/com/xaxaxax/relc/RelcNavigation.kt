package com.xaxaxax.relc

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

enum class TopLevelDestination(
    val route: Any,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    DISPLAYS(DisplaysRoute, Icons.Default.Tv, R.string.nav_displays),
    SCRIPTS(ScriptsRoute, Icons.Default.Code, R.string.nav_scripts),
    SETTINGS(SettingsRoute, Icons.Default.Settings, R.string.nav_settings),
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

@Serializable
object AboutRoute

@Serializable
object DeveloperOptionsRoute
