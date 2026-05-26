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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.shizuku.UserService

import androidx.compose.material3.OutlinedTextField
import java.io.File

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xaxaxax.relc.simplescript.ui.viewmodel.SimpleScriptViewModel
import com.xaxaxax.relc.simplescript.domain.model.Script

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayDetailScreen(
    id: String, onNavigateBack: () -> Unit,
    viewModel: DisplayDetailScreenViewModel = hiltViewModel(),
    simpleScriptViewModel: SimpleScriptViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val displayId = id.toIntOrNull() ?: -1
    var isReadOnly by remember { mutableStateOf(false) }

    val scripts by simpleScriptViewModel.scripts.collectAsStateWithLifecycle()
    var selectedScript by remember { mutableStateOf<Script?>(null) }
    var expanded by remember { mutableStateOf(false) }

    // Initialize with the first script if available and nothing is selected
    LaunchedEffect(scripts) {
        if (selectedScript == null && scripts.isNotEmpty()) {
            selectedScript = scripts.first()
        }
    }

    val scope = rememberCoroutineScope()
    val serviceFlow = remember {
        UserService.create(
            scope,
            RelcV2Service::class,
            IRelcV2Service.Stub::asInterface
        )
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

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selectedScript?.name ?: "No Script Selected",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Script Name") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    scripts.forEach { script ->
                        DropdownMenuItem(
                            text = { Text(script.name) },
                            onClick = {
                                selectedScript = script
                                expanded = false
                            }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val scriptName = selectedScript?.name ?: "default_script"
                    val scriptDir = File(context.filesDir, "scripts/$scriptName")
                    if (!scriptDir.exists()) scriptDir.mkdirs()

                    val intent = Intent(context, FullscreenDisplayActivity::class.java).apply {
                        putExtra("displayId", displayId)
                        putExtra("isReadOnly", isReadOnly)
                        putExtra("scriptDir", scriptDir.absolutePath)
                        putExtra("scriptId", selectedScript?.id ?: 0L)
                    }
                    context.startActivity(intent)
                },
                enabled = selectedScript != null
            ) {
                Text("Open Fullscreen")
            }

            Button(
                onClick = {
                    viewModel.closeDisplay(displayId)
                    onNavigateBack()
                },
                enabled = true
            ) {
                Text("Close Display")
            }
        }
    }
}
