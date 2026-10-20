package dev.owndemand.app

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.owndemand.app.ui.OwnDemandAppRoot
import dev.owndemand.app.ui.theme.OwnDemandTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as OwnDemandApp).container

    /** A video plays and the Picture-in-picture setting is on. */
    private var pipAllowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = container
        setContent {
            OwnDemandTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    OwnDemandAppRoot(container)
                }
            }
        }
        // Picture-in-picture (Chunk 4): Android 12+ shrinks the app into the
        // window by itself on Home / gesture-away; older versions use
        // onUserLeaveHint below.
        lifecycleScope.launch {
            combine(
                container.videoActive,
                container.settingsRepository.settings,
            ) { active, s -> active && s.pictureInPicture }
                .collect { allowed ->
                    pipAllowed = allowed
                    updatePipParams()
                }
        }
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        // The video's own shape, within what Android accepts (2.39:1 either way).
        val aspect = (container.playbackManagerOrNull()?.videoAspect?.value ?: (16f / 9f))
            .coerceIn(1f / 2.39f, 2.39f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(pipAllowed)
            }
            .build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && pipAllowed) {
            pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.setInPip(isInPictureInPictureMode)
        // Closed (not expanded): the activity is no longer started.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            container.onPipDismissed()
        }
    }

    // Foreground / background: the 15-minute auto-refresh on return, and
    // background playback (Chunk 4).
    override fun onStart() {
        super.onStart()
        container.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        container.onAppBackground()
    }
}
