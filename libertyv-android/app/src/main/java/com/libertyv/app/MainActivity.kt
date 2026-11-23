package com.libertyv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.libertyv.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LibertyTheme {
                AppRoot(vm)
            }
        }
    }
}

private val Green = Color(0xFF2E7D32)

@Composable
fun LibertyTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFFA5D6A7), tertiary = Color(0xFFFFB74D))
    } else {
        lightColorScheme(primary = Green, secondary = Color(0xFF558B2F), tertiary = Color(0xFFEF6C00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
