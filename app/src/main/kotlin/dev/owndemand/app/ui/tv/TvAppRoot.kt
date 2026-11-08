package dev.owndemand.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import dev.owndemand.app.AppContainer
import dev.owndemand.app.ui.PipVideo
import dev.owndemand.app.ui.common.LocalAppDensity
import dev.owndemand.app.ui.containerViewModel
import dev.owndemand.app.ui.main.MainViewModel
import dev.owndemand.app.ui.setup.LibraryPickerViewModel
import dev.owndemand.app.ui.setup.SetupViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android TV root (Chunk 6): the same settings-driven navigation as the
 * phone root (sign-in → server → library → main), with TV screens.
 */
@Composable
fun TvAppRoot(container: AppContainer) {
    val mainVm: MainViewModel = containerViewModel { MainViewModel(it) }
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    settings.server?.let { container.currentServer = it }
    settings.library?.let { container.currentSectionKey = it.key }

    // Hold the launch splash until the first screen has content (as on phones).
    LaunchedEffect(settings.loaded) {
        if (!settings.loaded) return@LaunchedEffect
        if (settings.isReady) {
            val index = container.libraryIndex
            withTimeoutOrNull(LAUNCH_WAIT_MS) {
                combine(index.items, index.error) { items, error -> items != null || error != null }
                    .first { it }
            }
        }
        container.markLaunchReady()
    }

    val inPip by container.inPip.collectAsStateWithLifecycle()
    if (inPip && settings.hasLibrary) {
        PipVideo(container.playbackManager())
        return
    }
    val system = LocalDensity.current
    val density = Density(system.density, system.fontScale * settings.fontScale.coerceIn(0.85f, 1.5f))
    CompositionLocalProvider(LocalDensity provides density, LocalAppDensity provides density) {
        TvTheme {
            // A surface so every text defaults to the scheme's on-background
            // colour (TV Material's own default is black).
            Surface(
                modifier = Modifier.fillMaxSize(),
                colors = SurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ),
            ) {
                when {
                    !settings.loaded -> Unit
                    !settings.isSignedIn || !settings.hasServer -> {
                        val vm: SetupViewModel = containerViewModel { SetupViewModel(it) }
                        TvConnectScreen(vm, signedIn = settings.isSignedIn)
                    }
                    !settings.hasLibrary -> {
                        val vm: LibraryPickerViewModel = containerViewModel { LibraryPickerViewModel(it) }
                        TvLibraryPicker(vm)
                    }
                    else -> TvShell(container, mainVm)
                }
            }
        }
    }
}

/** Longest the launch splash waits for the library's first load. */
private const val LAUNCH_WAIT_MS = 2_500L
