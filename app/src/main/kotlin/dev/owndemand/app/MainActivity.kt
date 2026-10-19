package dev.owndemand.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.owndemand.app.ui.OwnDemandAppRoot
import dev.owndemand.app.ui.theme.OwnDemandTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OwnDemandApp).container
        setContent {
            OwnDemandTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    OwnDemandAppRoot(container)
                }
            }
        }
    }
}
