package com.xaxaxax.relc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xaxaxax.relc.ui.theme.ReLCTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RelcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReLCTheme {
                RelcNavGraph()
            }
        }
    }
}
