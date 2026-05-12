package com.xaxaxax.relc.ui.scripts

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ScriptsScreen(onNavigateToDetail: (String) -> Unit) {
    LazyColumn {
        item {
            Text("Scripts Screen")
        }
        items(5) { id ->
            Button(onClick = { onNavigateToDetail(id.toString()) }) {
                Text("Script #$id")
            }
        }
    }
}