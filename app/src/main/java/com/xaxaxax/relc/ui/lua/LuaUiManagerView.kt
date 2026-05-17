package com.xaxaxax.relc.ui.lua

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xaxaxax.relc.lua.LuaNative
import timber.log.Timber
import java.io.File

@Composable
fun LuaUiManagerView(
    manager: LuaUiManager,
    luaNative: LuaNative
) {
    val context = LocalContext.current
    val scope = remember(context, luaNative) {
        object : LuaUiScope {
            override val rootPath: File = context.filesDir
            override fun sendUIEvent(id: String, event: String) {
                luaNative.sendUIEvent(id, "click")
            }
        }
    }
    LaunchedEffect(Unit) {
        Timber.d("Use LuaUiManagerView")
    }
    Row {
        with(scope) {
            manager.elements.filterKeys { k -> k.startsWith("__root") }.values.forEach { element ->
                element.GetComposable()
            }
        }
    }
}
