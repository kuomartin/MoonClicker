package com.xaxaxax.relc.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xaxaxax.relc.ui.theme.ReLCTheme
import timber.log.Timber

/**
 * Debug-only Activity for integration testing VirtualDisplayController.
 * Only exists in debug builds — declared in src/debug/AndroidManifest.xml.
 */
class VirtualDisplayDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber is here~~")
        }
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
    modifier: Modifier = Modifier,
    vm: VirtualDisplayDebugViewModel = viewModel(),
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
        TestArea(
            moveApp = { p, d -> vm.testMove(p, d) },
            openAppFunction = { p, d -> vm.openApp(p, d) },
            testInputFunction = { d -> vm.testInput(d) },
            runScriptFunction = { d -> vm.runTestScript(d) }
        )

        Button(
            onClick = { vm.debug("") },
        ) { Text("Debug") }


        // ── Create VD ──────────────────────────────────────────────────────
        Text("Create VirtualDisplay", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.createWithNoOp() },
                enabled = state.canCreate,
            ) { Text("NoOpSink") }

            Button(
                onClick = { vm.createWithDirectSink() },
                enabled = state.canCreate,
            ) { Text("DirectSink") }

            Button(
                onClick = { vm.createWithH264() },
                enabled = state.canCreate,
            ) { Text("H264Sink (TODO)") }
        }

        HorizontalDivider()
        // ── SurfaceView 預覽區（DirectSink 模式）──────────────────────────────
        if (state.showSurface) {
            val ctrl by vm.controllerState.collectAsState()
            ctrl?.let { controller ->
                Text("VirtualDisplay Preview", style = MaterialTheme.typography.titleMedium)
                VirtualDisplaySurfaceView(
                    controller = controller,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)   // 對應 1080×1920
                        .background(Color.Black),
                )
            }

            HorizontalDivider()
        }

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

@Composable
fun TestArea(
    moveApp: (packageName: String, displayId: Int) -> Unit,
    openAppFunction: (packageName: String, displayId: Int) -> Unit,
    testInputFunction: (displayId: Int) -> Unit,
    runScriptFunction: (displayId: Int) -> Unit,
) {
    var packageName: String by remember { mutableStateOf("moe.shizuku.privileged.api") }
    var displayId: Int by remember { mutableIntStateOf(0) }
    var text: String by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = text,
            onValueChange = { newText: String ->
                // Basic validation: only allow digits
                if (newText.all { it.isDigit() }) {
                    text = newText.trimStart('0')
                    displayId = text.toIntOrNull() ?: 0
                }
            },
            label = { Text("Enter Display ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        TextField(
            value = packageName,
            onValueChange = { packageName = it },
            label = { Text("Enter package name") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { moveApp(packageName, displayId) }) {
                Text("Move App")
            }

            Button(onClick = { openAppFunction(packageName, displayId) }) {
                Text("Launch App")
            }
            Button(onClick = { testInputFunction(displayId) }) {
                Text("Test Input")
            }
        }
        Button(onClick = { runScriptFunction(displayId) }, modifier = Modifier.fillMaxWidth()) {
            Text("Run Test Script (Lua)")
        }
    }
}
