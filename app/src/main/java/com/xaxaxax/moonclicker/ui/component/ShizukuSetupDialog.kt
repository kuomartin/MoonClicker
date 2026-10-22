package com.xaxaxax.moonclicker.ui.component

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.ui.theme.MoonClickerTheme

/** Shizuku 設定引導目前停在哪一步；宣告順序即步驟順序。 */
enum class ShizukuSetupStep { INSTALL, START, GRANT, DONE }

/**
 * 從安裝、啟動到授權一路引導的對話框。
 *
 * 使用者離開 App 去安裝或啟動 Shizuku 時對話框不關，回來後隨 Shizuku 狀態前進更新內容，
 * 使用者不必重新找入口；完成後由使用者自己關，最後一個打勾就是成功的確認。
 * 呼叫端的開關狀態要用 `rememberSaveable`：去安裝、啟動的期間 App 行程可能被回收。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuSetupDialog(
    onDismiss: () -> Unit,
    viewModel: ShizukuSetupViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsState()
    BasicAlertDialog(onDismissRequest = onDismiss) {
        ShizukuSetupDialogContent(
            step = step,
            onInstall = viewModel::install,
            onOpenShizuku = viewModel::openShizuku,
            onGrant = viewModel::requestPermission,
            onDismiss = onDismiss,
        )
    }
}

/** 與 Dialog window 分開，preview 才畫得出來（layoutlib 不渲染 Dialog window）。 */
@Composable
private fun ShizukuSetupDialogContent(
    step: ShizukuSetupStep,
    onInstall: () -> Unit,
    onOpenShizuku: () -> Unit,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_shizuku_icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                text = stringResource(R.string.shizuku_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 16.dp)
                    .align(Alignment.CenterHorizontally),
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.permission_shizuku_rationale_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SetupStepRow(
                    number = 1,
                    title = stringResource(R.string.shizuku_setup_step_install),
                    description = stringResource(R.string.shizuku_setup_step_install_desc),
                    actionLabel = stringResource(R.string.shizuku_setup_action_install),
                    onAction = onInstall,
                    state = stepState(ShizukuSetupStep.INSTALL, step),
                )
                SetupStepRow(
                    number = 2,
                    title = stringResource(R.string.shizuku_setup_step_start),
                    description = stringResource(R.string.shizuku_setup_step_start_desc),
                    actionLabel = stringResource(R.string.permission_action_open_shizuku),
                    onAction = onOpenShizuku,
                    state = stepState(ShizukuSetupStep.START, step),
                )
                SetupStepRow(
                    number = 3,
                    title = stringResource(R.string.shizuku_setup_step_grant),
                    description = stringResource(R.string.shizuku_setup_step_grant_desc),
                    actionLabel = stringResource(R.string.permission_action_grant),
                    onAction = onGrant,
                    state = stepState(ShizukuSetupStep.GRANT, step),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 24.dp),
            ) {
                if (step == ShizukuSetupStep.DONE) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.shizuku_setup_action_done))
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.permission_action_cancel))
                    }
                }
            }
        }
    }
}

private enum class StepState { COMPLETED, CURRENT, UPCOMING }

private fun stepState(row: ShizukuSetupStep, current: ShizukuSetupStep) = when {
    row < current -> StepState.COMPLETED
    row == current -> StepState.CURRENT
    else -> StepState.UPCOMING
}

@Composable
private fun SetupStepRow(
    number: Int,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    state: StepState,
) {
    Row(
        modifier = Modifier.alpha(if (state == StepState.UPCOMING) 0.38f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepIndicator(number = number, state = state)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (state == StepState.CURRENT) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(number: Int, state: StepState) {
    val colors = MaterialTheme.colorScheme
    val modifier = Modifier.size(24.dp)
    when (state) {
        StepState.COMPLETED -> Box(
            modifier = modifier.background(colors.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }

        StepState.CURRENT -> Box(
            modifier = modifier.background(colors.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onPrimary)
        }

        StepState.UPCOMING -> Box(
            modifier = modifier.border(1.dp, colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

private class ShizukuSetupStepProvider : PreviewParameterProvider<ShizukuSetupStep> {
    override val values = ShizukuSetupStep.entries.asSequence()
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun ShizukuSetupDialogPreview(
    @PreviewParameter(ShizukuSetupStepProvider::class) step: ShizukuSetupStep,
) {
    MoonClickerTheme {
        ShizukuSetupDialogContent(step = step, onInstall = {}, onOpenShizuku = {}, onGrant = {}, onDismiss = {})
    }
}

/** Interactive Mode 用：按主按鈕模擬 Shizuku 狀態前進，關閉後從頭開始。 */
@Preview
@Composable
private fun ShizukuSetupDialogInteractivePreview() {
    MoonClickerTheme {
        var step by remember { mutableStateOf(ShizukuSetupStep.INSTALL) }
        val advance = { step = ShizukuSetupStep.entries[step.ordinal + 1] }
        ShizukuSetupDialogContent(
            step = step,
            onInstall = advance,
            onOpenShizuku = advance,
            onGrant = advance,
            onDismiss = { step = ShizukuSetupStep.INSTALL },
        )
    }
}
