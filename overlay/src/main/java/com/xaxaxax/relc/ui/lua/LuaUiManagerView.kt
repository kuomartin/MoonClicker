package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.xaxaxax.relc.engine.LuaEngineControl
import timber.log.Timber
import java.io.File

@Composable
fun LuaUiManagerView(
    manager: LuaUiManager,
    scriptDir: String
) {
    val context = LocalContext.current
    val scope = remember(context, scriptDir) {
        object : LuaUiScope {
            override val rootPath: File = File(scriptDir)
            override val sharedData: Map<String, Any> = LuaEngineControl.sharedData
            override fun sendUIEvent(id: String, event: String) {
                LuaEngineControl.sendUIEvent(id, "click")
            }
        }
    }
    LaunchedEffect(Unit) {
        Timber.d("Use LuaUiManagerView")
    }
    Box(modifier = Modifier.fillMaxSize()) {
        with(scope) {
            manager.roots.forEach { element ->
                element.GetComposable(Modifier)
            }
        }
    }
}
