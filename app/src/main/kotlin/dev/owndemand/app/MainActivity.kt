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

    // Foreground / background, for the 15-minute auto-refresh on return.
    override fun onStart() {
        super.onStart()
        (application as OwnDemandApp).container.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        (application as OwnDemandApp).container.onAppBackground()
    }
}
