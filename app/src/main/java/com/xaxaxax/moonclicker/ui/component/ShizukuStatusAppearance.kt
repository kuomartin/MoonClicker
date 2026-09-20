package com.xaxaxax.moonclicker.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.shizuku.ShizukuConnectionStatus

/** 一個狀態該怎麼被說出來、用什麼顏色。 */
data class ShizukuStatusAppearance(val label: String, val tint: Color)

/**
 * 狀態列與設定頁共用同一份對映。
 *
 * 各寫一份的話，同一個狀態遲早會在兩個畫面上有兩個名字——而使用者看到的是同一件事。
 */
@Composable
fun shizukuStatusAppearance(status: ShizukuConnectionStatus): ShizukuStatusAppearance = when (status) {
    ShizukuConnectionStatus.NOT_AVAILABLE -> ShizukuStatusAppearance(
        stringResource(R.string.shizuku_status_not_available),
        MaterialTheme.colorScheme.error,
    )

    ShizukuConnectionStatus.NEED_PERMISSION -> ShizukuStatusAppearance(
        stringResource(R.string.shizuku_status_need_permission),
        MaterialTheme.colorScheme.error,
    )

    ShizukuConnectionStatus.DISCONNECTED -> ShizukuStatusAppearance(
        stringResource(R.string.shizuku_status_disconnected),
        MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ShizukuConnectionStatus.CONNECTING -> ShizukuStatusAppearance(
        stringResource(R.string.shizuku_status_connecting),
        MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ShizukuConnectionStatus.CONNECTED -> ShizukuStatusAppearance(
        stringResource(R.string.shizuku_status_connected),
        MaterialTheme.colorScheme.primary,
    )
}
