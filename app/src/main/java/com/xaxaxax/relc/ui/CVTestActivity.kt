package com.xaxaxax.relc.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CVTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CVTestScreen()
        }
    }
}

@Composable
fun CVTestScreen(viewModel: CVTestViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var packageName by remember { mutableStateOf("com.android.settings") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("OpenCV 辨識測試 (VirtualDisplay)", style = MaterialTheme.typography.headlineSmall)

        Button(onClick = {
            if (uiState.isSearching) viewModel.stopTest() else viewModel.startTest()
        }) {
            Text(if (uiState.isSearching) "停止測試 & 銷毀 VD" else "建立 VD 並開始辨識")
        }

        if (uiState.displayId != -1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("App Package Name") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { viewModel.openApp(packageName) },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Launch")
                }
            }
            Text("VirtualDisplay ID: ${uiState.displayId}")
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.latestResult?.isDetected == true)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("辨識狀態: ${if (uiState.latestResult?.isDetected == true) "✅ 找到了" else "❌ 未發現"}")
                uiState.latestResult?.let {
                    Text("中心點座標: (${it.centerX}, ${it.centerY})")
                    Text("相似度 (Confidence): ${"%.2f".format(it.confidenceRate)}")
                    Text("顏色差異 (ColorDiff): ${"%.2f".format(it.colorDiff)}")
                }
            }
        }

        Text("Logs:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(uiState.log.reversed()) { logMsg ->
                Text(logMsg, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}
