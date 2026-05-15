package com.xaxaxax.relc.ui.displaydetail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun DisplayDetailScreen(id: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val displayId = id.toIntOrNull() ?: -1
    var isReadOnly by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val serviceFlow = remember {
        UserService.create(
            scope,
            RelcV2Service::class,
            IRelcV2Service.Stub::asInterface
        )
    }
    val controllerState = remember { MutableStateFlow<VirtualDisplayController?>(null) }
    val controller by controllerState.collectAsState()

    remember(displayId) {
        scope.launch {
            serviceFlow.runWhenAlive { service ->
                val ctrl = VirtualDisplayController(service)
                // We don't necessarily need to attach here if we just want to destroy it,
                // but VirtualDisplayController.destroy() checks state == CREATED.
                // So we attach it first.
                ctrl.attach(displayId)
                controllerState.value = ctrl
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Display #$id", style = MaterialTheme.typography.headlineMedium)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp)
            ) {
                Checkbox(
                    checked = isReadOnly,
                    onCheckedChange = { isReadOnly = it }
                )
                Text(text = "Read-Only Mode")
            }

            Button(onClick = {
                val intent = Intent(context, FullscreenDisplayActivity::class.java).apply {
                    putExtra("displayId", displayId)
                    putExtra("isReadOnly", isReadOnly)
                }
                context.startActivity(intent)
            }) {
                Text("Open Fullscreen")
            }

            Button(
                onClick = {
                    controller?.destroy()
                    onNavigateBack()
                },
                enabled = controller != null
            ) {
                Text("Close Display")
            }
        }
    }
}
