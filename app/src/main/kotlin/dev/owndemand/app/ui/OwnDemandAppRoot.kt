package dev.owndemand.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.currentLayoutMode
import dev.owndemand.app.ui.main.MainShell
import dev.owndemand.app.ui.main.MainViewModel
import dev.owndemand.app.ui.setup.ConnectScreen
import dev.owndemand.app.ui.setup.LibraryPickerScreen
import dev.owndemand.app.ui.setup.LibraryPickerViewModel
import dev.owndemand.app.ui.setup.SetupViewModel

/**
 * Root navigation, derived from persisted settings rather than imperative
 * navigation — this makes process death and restart land in the right place:
 *   no token            → Connect (sign in)
 *   token, no server    → Connect (server selection)
 *   server, no library  → Library picker
 *   everything set      → Main shell (watch screen is state inside it)
 */
@Composable
fun OwnDemandAppRoot(container: dev.owndemand.app.AppContainer) {
    val mainVm: MainViewModel = containerViewModel { MainViewModel(it) }
    val settings by mainVm.settings.collectAsStateWithLifecycle()

    // Keep the container in step with the very snapshot driving this
    // decision: children (MainShell -> playbackManager) may compose before
    // MainViewModel's collector coroutine has run, and must never see a
    // null server once hasServer is true (cold-start crash 2026-10-16).
    settings.server?.let { container.currentServer = it }
    settings.library?.let { container.currentSectionKey = it.key }

    // App-wide text size (user request 2026-10-16): multiply the system
    // font scale by the Settings slider. Overriding density here scales
    // every sp size in the app — including the Settings screen itself, so
    // the slider previews live — while dp layout stays put.
    val systemDensity = LocalDensity.current
    val fontScale = settings.fontScale.coerceIn(0.85f, 1.5f)
    // Window-size classification for adaptive layouts (TABLET-PLAN §2):
    // derived from the live Configuration so rotating or resizing the
    // window recomposes screens in place — MainActivity declares
    // configChanges, so nothing restarts and playback survives.
    val layoutMode = currentLayoutMode()
    // Picture-in-picture (Chunk 4): the small window shows the video only.
    val inPip by container.inPip.collectAsStateWithLifecycle()
    if (inPip && settings.hasLibrary) {
        PipVideo(container.playbackManager().player)
        return
    }
    CompositionLocalProvider(
        LocalDensity provides
            Density(systemDensity.density, systemDensity.fontScale * fontScale),
        LocalLayoutMode provides layoutMode,
    ) {
        when {
            !settings.isSignedIn || !settings.hasServer -> {
                val vm: SetupViewModel = containerViewModel { SetupViewModel(it) }
                ConnectScreen(vm, signedIn = settings.isSignedIn)
            }

            !settings.hasLibrary -> {
                val vm: LibraryPickerViewModel = containerViewModel { LibraryPickerViewModel(it) }
                LibraryPickerScreen(vm)
            }

            // The watch screen is a state inside the shell (MainViewModel),
            // so the bottom menu stays visible while playing portrait.
            else -> MainShell(mainVm = mainVm)
        }
    }
}

/**
 * The picture-in-picture window: the playing video, letterboxed on black.
 * A SurfaceView here (checked on the Tab S6): the system composites and
 * scales it itself through PiP transitions. The in-app player keeps its
 * TextureView for zoom / pan.
 */
@Composable
private fun PipVideo(player: androidx.media3.exoplayer.ExoPlayer) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            factory = { ctx ->
                android.view.LayoutInflater.from(ctx)
                    .inflate(dev.owndemand.app.R.layout.player_surface_pip, null, false)
                    as androidx.media3.ui.PlayerView
            },
            update = { it.player = player },
            // Hand the player back cleanly when PiP ends.
            onRelease = { it.player = null },
        )
    }
}
