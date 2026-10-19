package dev.owndemand.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.owndemand.app.data.PlexRepository
import dev.owndemand.app.data.plex.PlexHeaders
import dev.owndemand.app.data.plex.dto.ItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the one ExoPlayer instance and Plex timeline reporting
 * (resume + watched state). The app decides direct vs transcode
 * (PlaybackDecision); Media3 only plays what it is given.
 *
 * M6 adds: persistent playback speed, SRT caption toggle (Youtarr ships
 * English sidecars), a data-saver transcode mode, and automatic recovery
 * from network-drop errors.
 */
class PlaybackManager(
    context: Context,
    private val repository: PlexRepository,
    clientIdentifier: String,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TIMELINE_INTERVAL_MS = 10_000L

        /** Scrub-preview bucket: BIF keyframes are ~10 s apart. */
        private const val PREVIEW_BUCKET_MS = 5_000L

        /** Network-drop recovery attempts and backoff (M6). */
        private const val MAX_NETWORK_RETRIES = 4
        private val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
    }

    /** Data saver: always transcode capped to 480p/1200k (Settings tab). */
    @Volatile
    var dataSaver: Boolean = false

    // PMS returns 400 for the transcode endpoints unless the standard
    // X-Plex-* identification headers accompany the token (M3 HLS fix).
    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(
            PlexHeaders.identification(clientIdentifier) +
                mapOf("X-Plex-Token" to repository.accessToken),
        )
        // Never follow an https→http redirect: the token header would leak.
        .setAllowCrossProtocolRedirects(false)

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) {
                            // Playback resumed → recovery worked (or a
                            // fresh start); clear the retry budget.
                            networkRetries = 0
                            _reconnecting.value = false
                            _error.value = null
                            startTimelineLoop()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (isNetworkError(error) && _currentItem.value != null &&
                            networkRetries < MAX_NETWORK_RETRIES
                        ) {
                            retryAfterDrop()
                        } else {
                            _reconnecting.value = false
                            _error.value = error.errorCodeName
                        }
                    }
                })
            }
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while auto-retrying after a network drop (M6). */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /** Current playback speed; kept across items (user picks it once). */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Captions on/off; the choice persists across videos. */
    private val _captionsEnabled = MutableStateFlow(false)
    val captionsEnabled: StateFlow<Boolean> = _captionsEnabled.asStateFlow()

    /** Whether the loaded item has a playable subtitle sidecar (CC UI). */
    private val _hasSubtitles = MutableStateFlow(false)
    val hasSubtitles: StateFlow<Boolean> = _hasSubtitles.asStateFlow()

    /** The item currently loaded in the player; drives the miniplayer. */
    private val _currentItem = MutableStateFlow<ItemDto?>(null)
    val currentItem: StateFlow<ItemDto?> = _currentItem.asStateFlow()

    /**
     * Part id of the loaded item when it has BIF preview frames
     * (`indexes="sd"`); null = no scrub previews. Times are bucketed so
     * Coil caches frames between BIF keyframes.
     */
    private var previewPartId: Long? = null

    /**
     * Whole BIF scrub-preview strip for the loaded item, fetched in the
     * background the moment playback starts (user report 2026-10-16:
     * on-demand per-frame fetches made the first scrub slow). Null until
     * parsed; the scrubber falls back to per-frame URLs until then.
     */
    private val _bifIndex = MutableStateFlow<dev.owndemand.app.data.plex.BifIndex?>(null)
    val bifIndex: StateFlow<dev.owndemand.app.data.plex.BifIndex?> = _bifIndex.asStateFlow()

    /** In-memory preview JPEG for a scrub position, once the strip is in. */
    fun previewFrame(timeMs: Long): ByteArray? = _bifIndex.value?.frameAt(timeMs)

    private var networkRetries = 0
    private var retryJob: Job? = null

    fun previewUrl(timeMs: Long): String? {
        val partId = previewPartId ?: return null
        val bucket = timeMs.coerceAtLeast(0L) / PREVIEW_BUCKET_MS * PREVIEW_BUCKET_MS
        return repository.previewFrameUrl(partId, bucket)
    }

    private var timelineJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
            }
        })
    }

    val currentRatingKey: String? get() = _currentItem.value?.ratingKey

    fun play(item: ItemDto) {
        _currentItem.value = item
        _error.value = null
        _reconnecting.value = false
        networkRetries = 0
        retryJob?.cancel()
        previewPartId = item.Media.firstOrNull()?.Part
            ?.firstOrNull { it.hasPreviewFrames }?.id
        _bifIndex.value = null
        previewPartId?.let { partId ->
            val forKey = item.ratingKey
            scope.launch {
                val parsed = runCatching {
                    dev.owndemand.app.data.plex.BifIndex.parse(repository.bifBytes(partId))
                }.onFailure {
                    android.util.Log.w("OwnDemand", "BIF fetch failed (part $partId)", it)
                }.getOrNull()
                // Still the same video? Show the strip.
                if (parsed != null && _currentItem.value?.ratingKey == forKey) {
                    _bifIndex.value = parsed
                }
            }
        }
        val subtitle = repository.subtitleUri(item)
        _hasSubtitles.value = subtitle != null
        player.setPlaybackSpeed(_speed.value)
        val (uri, mime) = repository.streamUri(item, dataSaver)
        val builder = MediaItem.Builder()
            .setUri(uri)
            .apply { mime?.let { setMimeType(it) } }
            .setMediaId(item.ratingKey)
        if (subtitle != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(
                        android.net.Uri.parse(subtitle.first),
                    )
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("en")
                        .setLabel(subtitle.second)
                        .build(),
                ),
            )
        }
        player.setMediaItem(builder.build())
        applyCaptions()
        player.prepare()
        if (item.viewOffset > 0) player.seekTo(item.viewOffset)
        player.playWhenReady = true
        report("buffering")
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun setSpeed(factor: Float) {
        _speed.value = factor
        player.setPlaybackSpeed(factor)
    }

    fun toggleCaptions() {
        _captionsEnabled.value = !_captionsEnabled.value
        applyCaptions()
    }

    private fun applyCaptions() {
        // The sidecar has no Plex "default" flag, so enabling the TEXT
        // type alone is not enough — steer selection with the language.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !_captionsEnabled.value)
            .setPreferredTextLanguage(if (_captionsEnabled.value) "en" else null)
            .build()
    }

    /** Network-drop errors are worth retrying; 404s and codec failures are not. */
    private fun isNetworkError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW

    /** Re-prepare the same item from the last position with backoff. */
    private fun retryAfterDrop() {
        val position = player.currentPosition.coerceAtLeast(0)
        _reconnecting.value = true
        retryJob?.cancel()
        retryJob = scope.launch {
            val attempt = networkRetries++
            delay(RETRY_BACKOFF_MS[attempt.coerceAtMost(RETRY_BACKOFF_MS.size - 1)])
            if (_currentItem.value == null) return@launch
            player.seekTo(position)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Stop playback and report the position for resume. */
    fun stopAndReport() {
        report("stopped")
        timelineJob?.cancel()
        retryJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _currentItem.value = null
        _isPlaying.value = false
        _reconnecting.value = false
        previewPartId = null
        _bifIndex.value = null
    }

    private fun startTimelineLoop() {
        if (timelineJob?.isActive == true) return
        timelineJob = scope.launch {
            while (true) {
                delay(TIMELINE_INTERVAL_MS)
                if (!player.isPlaying) break
                report("playing")
            }
        }
    }

    private fun report(state: String) {
        val item = _currentItem.value ?: return
        val ratingKey = item.ratingKey
        val time = player.currentPosition.coerceAtLeast(0)
        val duration = if (player.duration > 0) player.duration else item.duration
        scope.launch {
            repository.reportTimeline(ratingKey, state, time, duration)
        }
    }
}
