package dev.owndemand.app

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewTreeObserver
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
        holdSplashUntilReady(container)
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

    /**
     * Launch splash (polish pass): the first frame is held — on Android 12+
     * that keeps the system splash (icon + wordmark) up, before that the
     * splash window background — until the first screen has content
     * ([AppContainer.launchReady]), so a launch never flashes the sign-in
     * screen or an empty feed. Hard-capped in case readiness never comes.
     */
    private fun holdSplashUntilReady(container: AppContainer) {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!container.launchReady.value) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    // Below Android 12 the window background is the splash
                    // itself; from here on it is just the app background.
                    window.setBackgroundDrawableResource(R.color.app_background)
                    return true
                }
            },
        )
        lifecycleScope.launch {
            kotlinx.coroutines.delay(SPLASH_MAX_MS)
            container.markLaunchReady()
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

/** Longest the launch splash can stay up, whatever happens. */
private const val SPLASH_MAX_MS = 4_000L
