package com.xaxaxax.relc.ui.scriptdetail.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 簡易腳本編輯器 / 步驟卡片的色票，一律從 [MaterialTheme.colorScheme] 衍生。
 */
data class SimpleScriptUiColors(
    val stepCardContainer: Color,
    val errorStepCardContainer: Color,
    val divider: Color,
    val verbMenuLabel: Color,
    val destructive: Color,
    val destructiveMuted: Color,
    val onErrorStepTitle: Color,
    val scriptLogBackground: Color,
    val scriptLogText: Color,
)

@Composable
@ReadOnlyComposable
fun simpleScriptUiColors(): SimpleScriptUiColors {
    val s = MaterialTheme.colorScheme
    return SimpleScriptUiColors(
        stepCardContainer = s.surfaceContainerHigh,
        errorStepCardContainer = s.errorContainer,
        divider = s.outlineVariant.copy(alpha = 0.5f),
        verbMenuLabel = s.onSurfaceVariant,
        destructive = s.error,
        destructiveMuted = s.onSurface.copy(alpha = 0.38f),
        onErrorStepTitle = s.onErrorContainer,
        scriptLogBackground = s.inverseSurface,
        scriptLogText = s.primary,
    )
}
