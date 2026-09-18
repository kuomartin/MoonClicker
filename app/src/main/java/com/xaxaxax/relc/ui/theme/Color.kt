package com.xaxaxax.relc.ui.theme

import androidx.compose.ui.graphics.Color

// --- 暗色主題 (Dark Scheme) ---
val DarkBackground = Color(0xFF0D0E15)
val DarkSurface = Color(0xFF161822)

// 提高 SurfaceVariant 亮度與紫藍底蘊，讓卡片與 Switch 未開啟軌道有明確輪廓
val DarkSurfaceVariant = Color(0xFF2C2F42)

// 藍色主色 (用於 Switch 開啟時的 Thumb 與 Track)
val DarkPrimary = Color(0xFF7AA2F7)
val DarkOnPrimary = Color(0xFF081C44)
val DarkPrimaryContainer = Color(0xFF1E356A)
val DarkOnPrimaryContainer = Color(0xFFD6E2FF)

// 紫色輔色
val DarkSecondary = Color(0xFFBB9AF7)
val DarkOnSecondary = Color(0xFF2B154D)
val DarkSecondaryContainer = Color(0xFF432B68)
val DarkOnSecondaryContainer = Color(0xFFEADBFF)

val DarkTertiary = Color(0xFFC0CAF5)

// 文字與邊框調整：
// 1. DarkOutline 提亮為清晰的冷青灰，解決 Switch off 像 disabled 的關鍵
// 2. DarkOnSurfaceVariant 提亮，讓未選中時的 Thumb（圓鈕）對比鮮明
val DarkOnSurface = Color(0xFFF0F1F8)
val DarkOnSurfaceVariant = Color(0xFFB5BAD4)
val DarkOutline = Color(0xFF5B6282)             // 原本偏暗，提亮後讓 Off 軌道外框輪廓銳利清晰


// --- 亮色主題 (Light Scheme) ---
val LightBackground = Color(0xFFF7F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE4E7F4)

val LightPrimary = Color(0xFF3B5FB5)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDBE1FF)
val LightOnPrimaryContainer = Color(0xFF00174B)

val LightSecondary = Color(0xFF6B4FA0)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFEEDBFF)
val LightOnSecondaryContainer = Color(0xFF260856)

val LightTertiary = Color(0xFF5A5C7E)
val LightOnSurface = Color(0xFF181A22)
val LightOnSurfaceVariant = Color(0xFF4B4F63)
val LightOutline = Color(0xFF767B96)

// --- 語意色 (Semantic) ---
// 權限/狀態「已授予」，深淺主題共用
val SuccessColor = Color(0xFF4CAF50)