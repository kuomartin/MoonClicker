package com.xaxaxax.relc.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xaxaxax.relc.ui.theme.ReLCTheme

/**
 * Debug-only Activity for integration testing VirtualDisplayController.
 * Only exists in debug builds — declared in src/debug/AndroidManifest.xml.
 */
class VirtualDisplayDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReLCTheme {
                Scaffold { padding ->
                    VirtualDisplayDebugScreen(
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
private fun VirtualDisplayDebugScreen(
    vm: VirtualDisplayDebugViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by vm.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("VirtualDisplay Debug", style = MaterialTheme.typography.titleLarge)
        Text("State: ${state.controllerState}  DisplayId: ${state.displayId}")

        HorizontalDivider()

        // ── Create VD ──────────────────────────────────────────────────────
        Text("Create VirtualDisplay", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.createWithNoOp() },
                enabled = state.canCreate,
            ) { Text("NoOpSink") }

            Button(
                onClick = { vm.createWithH264() },
                enabled = state.canCreate,
            ) { Text("H264Sink (TODO)") }
        }

        HorizontalDivider()

        // ── Launch App ─────────────────────────────────────────────────────
        Text("Launch App in Display", style = MaterialTheme.typography.titleMedium)
        var packageName by remember { mutableStateOf("com.android.settings") }
        OutlinedTextField(
            value = packageName,
            onValueChange = { packageName = it },
            label = { Text("Package name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { vm.launch(packageName) },
            enabled = state.canLaunch,
        ) { Text("Launch") }

        HorizontalDivider()

        // ── Destroy VD ─────────────────────────────────────────────────────
        Button(
            onClick = { vm.destroy() },
            enabled = state.canDestroy,
        ) { Text("Destroy VirtualDisplay") }

        HorizontalDivider()

        // ── Log ────────────────────────────────────────────────────────────
        Text("Log", style = MaterialTheme.typography.titleMedium)
        Text(
            text = state.log.takeLast(20).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
