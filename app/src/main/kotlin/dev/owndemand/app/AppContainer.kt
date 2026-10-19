package dev.owndemand.app

import android.content.Context
import android.os.Build
import dev.owndemand.app.data.PlexRepository
import dev.owndemand.app.data.plex.PlexHeadersInterceptor
import dev.owndemand.app.data.plex.PlexTvClient
import dev.owndemand.app.data.plex.PmsClient
import dev.owndemand.app.data.settings.SettingsRepository
import dev.owndemand.app.data.settings.TokenStore
import dev.owndemand.app.domain.PlexServer
import dev.owndemand.app.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container (AGENTS.md: no unnecessary DI framework).
 * One instance lives in [OwnDemandApp]; ViewModels receive what they need
 * through this container.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val tokenStore = TokenStore()
    val settingsRepository = SettingsRepository(appContext, tokenStore)

    /**
     * Shared HTTP client. Identification headers are injected by the
     * interceptor; the client identifier is read lazily from settings so the
     * same OkHttp instance survives identifier creation at startup.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                PlexHeadersInterceptor(
                    clientIdentifier = { lazyClientId },
                    deviceName = Build.MODEL,
                    platformVersion = Build.VERSION.SDK_INT.toString(),
                ),
            )
            .build()
    }

    /** Set by [OwnDemandApp] right after the identifier is ensured. */
    @Volatile
    var lazyClientId: String = ""

    val plexTvClient: PlexTvClient by lazy { PlexTvClient(httpClient) }

    fun pmsClient(server: PlexServer): PmsClient = PmsClient(httpClient, server)

    // ---- Playback-scoped state (set by the shell once settings are ready) ----

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var currentServer: PlexServer? = null

    @Volatile
    var currentSectionKey: String? = null

    private var playbackManager: PlaybackManager? = null

    /** Whole-library in-memory index powering search/sort/home mix (M5). */
    val libraryIndex: dev.owndemand.app.data.LibraryIndex = dev.owndemand.app.data.LibraryIndex()

    fun repository(): PlexRepository {
        val server = currentServer ?: error("repository used before a server was selected")
        return PlexRepository(pmsClient(server), server)
    }

    fun playbackManager(): PlaybackManager {
        playbackManager?.let { return it }
        return PlaybackManager(appContext, repository(), lazyClientId, appScope)
            .also { playbackManager = it }
    }

    /** Releases the player (sign-out / server change); recreate afterwards. */
    fun releasePlayback() {
        playbackManager?.let { runCatching { it.player.release() } }
        playbackManager = null
    }
}
