package com.xaxaxax.relc.ui.displaydetail

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun DisplayDetailScreen(id: String) {
    val context = LocalContext.current
    val displayId = id.toIntOrNull() ?: -1

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = {
            val intent = Intent(context, FullscreenDisplayActivity::class.java).apply {
                putExtra("displayId", displayId)
            }
            context.startActivity(intent)
        }) {
            Text("Open Fullscreen (Display #$id)")
        }
    }
}
