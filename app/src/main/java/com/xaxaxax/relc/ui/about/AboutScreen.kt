package com.xaxaxax.relc.ui.about

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.xaxaxax.relc.BuildConfig
import com.xaxaxax.relc.R

private const val REPO_URL = "https://github.com/kuomartin/ReLC"
private const val ISSUES_URL = "$REPO_URL/issues"
private const val LICENSE_URL = "$REPO_URL/blob/master/LICENSE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val message by viewModel.message.collectAsState()

    // 連點解鎖時每一下都要立刻有回饋；系統的 Toast 佇列預設會排隊播放，快速連點會讓提示
    // 卡在上一個還沒播完，感覺像「點了沒反應」。蓋掉前一個而不是排隊，比照 Android 系統
    // 開發人員選項自己的做法。
    var activeToast by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        activeToast?.cancel()
        activeToast = Toast.makeText(context, text, Toast.LENGTH_SHORT).apply { show() }
        viewModel.consumeMessage()
    }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // painterResource() 不支援 adaptive-icon XML（mipmap-anydpi/ic_launcher.xml），
            // 用 Coil 的 AsyncImage 走 Drawable 載入才吃得下這個格式。
            AsyncImage(
                model = R.mipmap.ic_launcher,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = viewModel::onVersionTapped),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            AboutLinkRow(
                label = stringResource(R.string.about_source_code),
                value = REPO_URL,
                onClick = { openUrl(REPO_URL) },
            )
            AboutLinkRow(
                label = stringResource(R.string.about_report_issue),
                value = ISSUES_URL,
                onClick = { openUrl(ISSUES_URL) },
            )
            AboutLinkRow(
                label = stringResource(R.string.about_license),
                value = stringResource(R.string.about_license_value),
                onClick = { openUrl(LICENSE_URL) },
            )
        }
    }
}

@Composable
private fun AboutLinkRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
