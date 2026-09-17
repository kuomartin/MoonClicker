package com.xaxaxax.relc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = BackgroundDark,
    secondary = AccentDark,
    tertiary = AccentDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = OnSurfaceDark,
    outline = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = SurfaceLight,
    secondary = AccentLight,
    tertiary = AccentLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = BackgroundLight,
    onSurfaceVariant = OnSurfaceLight,
    outline = OutlineLight
)

@Composable
fun ReLCTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
