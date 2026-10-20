package dev.owndemand.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OwnDemandApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Ensure the stable client identifier exists before any request uses it.
        CoroutineScope(SupervisorJob()).launch {
            container.lazyClientId = container.settingsRepository.ensureClientIdentifier()
            container.settingsRepository.ensureQualitySettings()
        }
    }
}
