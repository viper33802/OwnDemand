package dev.owndemand.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import dev.owndemand.app.data.ConnectionManager
import dev.owndemand.app.data.PlexRepository
import dev.owndemand.app.data.plex.PlexHeadersInterceptor
import dev.owndemand.app.data.plex.PlexTvClient
import dev.owndemand.app.data.plex.PmsClient
import dev.owndemand.app.data.settings.SettingsRepository
import dev.owndemand.app.data.settings.TokenStore
import dev.owndemand.app.domain.PlexServer
import dev.owndemand.app.playback.PlaybackManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient

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
     * Base HTTP client: identification headers only. Address probes use it
     * directly so a failed probe never triggers another address check.
     */
    private val baseHttpClient: OkHttpClient by lazy {
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

    /**
     * Shared HTTP client. Identification headers are injected by the
     * interceptor; the client identifier is read lazily from settings so the
     * same OkHttp instance survives identifier creation at startup. A
     * network-level failure talking to the server schedules an address
     * re-check, so the next attempt (Retry, pull-to-refresh) lands on a
     * reachable address.
     */
    val httpClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .addInterceptor(Interceptor { chain ->
                try {
                    chain.proceed(chain.request())
                } catch (e: IOException) {
                    val host = chain.request().url.host
                    if (currentServer?.baseUrl?.contains(host) == true) recheckConnectionSoon()
                    throw e
                }
            })
            .build()
    }

    /** Set by [OwnDemandApp] right after the identifier is ensured. */
    @Volatile
    var lazyClientId: String = ""

    val plexTvClient: PlexTvClient by lazy { PlexTvClient(httpClient) }

    fun pmsClient(server: PlexServer): PmsClient = PmsClient(httpClient, server)

    /** Keeps [currentServer] on a reachable address (home / remote / relay). */
    val connections: ConnectionManager by lazy { ConnectionManager(this, baseHttpClient) }

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
        val manager = PlaybackManager(
            appContext,
            // Always the current address: it can change mid-session.
            { repository() },
            lazyClientId,
            appScope,
            // A video finished / was left: re-read it so watched chips,
            // Continue watching and resume offsets reflect the server.
            onItemSettled = { key -> appScope.launch { libraryIndex.refreshItem(repository(), key) } },
            // A network drop mid-playback: maybe the address changed.
            recoverAddress = { connections.ensureReachable(maxAgeMs = 0) },
            // "Next" from the notification / headset: Up-next order.
            nextCandidates = { libraryIndex.items.value.orEmpty() },
        )
        playbackManager = manager
        manager.setBackgroundMode(!appVisible || _inPip.value)
        manager.autoplayEnabled = autoplayEnabled
        playbackJob = appScope.launch {
            launch { manager.wantsToPlay.collect { _videoActive.value = it } }
            launch { manager.currentItem.collect { _playingItem.value = it } }
            // Watched state / resume offsets in the channel being played.
            launch { libraryIndex.updates.collect { manager.patchQueueItem(it) } }
            // The first video starts the media session (notification, lock
            // screen, headset buttons, background audio).
            launch { manager.currentItem.collect { if (it != null) bindPlaybackService() } }
        }
        return manager
    }

    private var playbackJob: Job? = null

    /** The player, if one was created (never creates it). */
    fun playbackManagerOrNull(): PlaybackManager? = playbackManager

    // ---- Playback outside the app (Chunk 4) ----

    /** Notification / lock-screen artwork, fetched with the app's own client. */
    val artworkLoader: dev.owndemand.app.playback.ArtworkBitmapLoader by lazy {
        dev.owndemand.app.playback.ArtworkBitmapLoader(
            fetch = { path ->
                val url = repository().artworkUrl(path)
                httpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
            },
            scope = appScope,
        )
    }

    /** Our own connection to [PlaybackService]: keeps the session alive. */
    private var sessionController: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    private fun bindPlaybackService() {
        if (sessionController != null) return
        val token = androidx.media3.session.SessionToken(
            appContext,
            android.content.ComponentName(appContext, dev.owndemand.app.playback.PlaybackService::class.java),
        )
        sessionController = androidx.media3.session.MediaController.Builder(appContext, token).buildAsync()
    }

    private fun unbindPlaybackService() {
        sessionController?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        sessionController = null
        appContext.stopService(android.content.Intent(appContext, dev.owndemand.app.playback.PlaybackService::class.java))
    }

    /** The app was swiped away from Recents: end playback, like closing the miniplayer. */
    fun stopPlaybackFromSystem() {
        playbackManager?.stopAndReport()
    }

    /** The Autoplay setting (in the background the next video plays at once). */
    @Volatile
    var autoplayEnabled: Boolean = true
        set(value) {
            field = value
            playbackManager?.autoplayEnabled = value
        }

    private val _playingItem = kotlinx.coroutines.flow.MutableStateFlow<dev.owndemand.app.data.plex.dto.ItemDto?>(null)

    /** The video in the player, whichever player instance is current. */
    val playingItem: kotlinx.coroutines.flow.StateFlow<dev.owndemand.app.data.plex.dto.ItemDto?> = _playingItem

    /** A video is loaded and playing (or about to): leaving the app enters PiP. */
    private val _videoActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val videoActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _videoActive

    private val _inPip = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** The activity is in picture-in-picture: the UI shows the video only. */
    val inPip: kotlinx.coroutines.flow.StateFlow<Boolean> = _inPip

    /** Activity started (visible, PiP included). */
    @Volatile
    private var appVisible = true

    /** Settings: keep playing after leaving the app (not in PiP). */
    @Volatile
    var backgroundPlayEnabled: Boolean = true

    fun setInPip(inPip: Boolean) {
        _inPip.value = inPip
        updateBackgroundMode()
    }

    /** The PiP window was closed: the video pauses (it stays in the notification). */
    fun onPipDismissed() {
        playbackManager?.player?.pause()
    }

    private fun updateBackgroundMode() {
        playbackManager?.setBackgroundMode(!appVisible || _inPip.value)
    }

    // ---- Video actions (long-press menu) ----

    /** Marks a video watched on the server, then refreshes it everywhere. */
    suspend fun markWatched(ratingKey: String) {
        repository().scrobble(ratingKey)
        libraryIndex.refreshItem(repository(), ratingKey)
    }

    /** Marks a video unwatched (clears progress), then refreshes it everywhere. */
    suspend fun markUnwatched(ratingKey: String) {
        repository().unscrobble(ratingKey)
        libraryIndex.refreshItem(repository(), ratingKey)
    }

    /** Releases the player (sign-out / server change); recreate afterwards. */
    fun releasePlayback() {
        unbindPlaybackService()
        playbackJob?.cancel()
        playbackJob = null
        _videoActive.value = false
        _playingItem.value = null
        playbackManager?.let { runCatching { it.player.release() } }
        playbackManager = null
    }

    // ---- Address re-checks: failures, network changes, returning to the app ----

    private val recheckPending = AtomicBoolean(false)

    /** Coalesces bursts of failures (a screen of thumbnails) into one re-check. */
    private fun recheckConnectionSoon() {
        if (!recheckPending.compareAndSet(false, true)) return
        appScope.launch {
            try {
                delay(500)
                recheckConnection()
            } finally {
                recheckPending.set(false)
            }
        }
    }

    /** Probes now; if the address changed, reloads the library and moves the player. */
    suspend fun recheckConnection() {
        if (currentServer == null) return
        if (connections.ensureReachable(maxAgeMs = 0)) onAddressChanged()
    }

    private fun onAddressChanged() {
        playbackManager?.onAddressChanged()
        appScope.launch { libraryIndex.refresh(this@AppContainer) }
    }

    private var networkJob: Job? = null

    private val _metered = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True on mobile data / metered networks: picks the mobile quality cap. */
    val metered: kotlinx.coroutines.flow.StateFlow<Boolean> = _metered

    init {
        // Wi-Fi ↔ mobile data (leaving or reaching home): re-check the address
        // once the new network has settled.
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                caps: android.net.NetworkCapabilities,
            ) {
                _metered.value =
                    !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }

            override fun onAvailable(network: Network) {
                appScope.launch {
                    networkJob?.cancel()
                    networkJob = launch {
                        delay(1_500)
                        recheckConnection()
                    }
                }
            }
        })
    }

    /** Wall-clock time of the last move to the background. */
    @Volatile
    private var backgroundedAtMs: Long? = null

    fun onAppBackground() {
        backgroundedAtMs = System.currentTimeMillis()
        appVisible = false
        // Background audio off: leaving the app pauses (PiP keeps playing).
        if (!backgroundPlayEnabled && !_inPip.value) playbackManager?.player?.pause()
        updateBackgroundMode()
    }

    /**
     * Back in the foreground: after [AUTO_REFRESH_AFTER_MS] away, re-check
     * the address and quietly reload the library (new downloads, watched
     * states) — the same as a pull-to-refresh on Home.
     */
    fun onAppForeground() {
        appVisible = true
        updateBackgroundMode()
        val away = backgroundedAtMs
        backgroundedAtMs = null
        if (!shouldRefreshOnReturn(away, System.currentTimeMillis())) return
        if (currentServer == null) return
        appScope.launch {
            connections.ensureReachable(maxAgeMs = 0)
            libraryIndex.refresh(this@AppContainer)
        }
    }

    companion object {
        /** Away at least this long → refresh on return (user choice 2026-10-19). */
        const val AUTO_REFRESH_AFTER_MS = 15 * 60 * 1000L
    }
}

/** True when the app was in the background for at least [thresholdMs]. */
fun shouldRefreshOnReturn(
    backgroundedAtMs: Long?,
    nowMs: Long,
    thresholdMs: Long = AppContainer.AUTO_REFRESH_AFTER_MS,
): Boolean = backgroundedAtMs != null && nowMs - backgroundedAtMs >= thresholdMs
