package com.xaxaxax.relc.ui.displays

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun DisplaysScreen(onNavigateToDetail: (String) -> Unit) {
    LazyColumn {
        item {
            Text("Displays Screen")
        }
        items(5) { id ->
            Button(onClick = { onNavigateToDetail(id.toString()) }) {
                Text("Display #$id")
            }
        }
    }
}
