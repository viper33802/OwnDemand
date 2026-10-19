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
    /** The repository for the server's *current* address (it can change). */
    private val repositoryProvider: () -> PlexRepository,
    clientIdentifier: String,
    private val scope: CoroutineScope,
    /**
     * Called with a video's ratingKey once its final report has reached
     * the server — it finished, was stopped, or another video replaced
     * it — so the app can re-read its watched state / resume offset.
     */
    private val onItemSettled: (String) -> Unit = {},
    /**
     * Called before retrying after a network drop; returns true when the
     * server's address changed (e.g. left home Wi-Fi), in which case the
     * video restarts from the new address at the same position.
     */
    private val recoverAddress: suspend () -> Boolean = { false },
) {
    private val repository: PlexRepository get() = repositoryProvider()

    companion object {
        private const val TIMELINE_INTERVAL_MS = 10_000L

        /** Scrub-preview bucket: BIF keyframes are ~10 s apart. */
        private const val PREVIEW_BUCKET_MS = 5_000L

        /** Network-drop recovery attempts and backoff (M6). */
        private const val MAX_NETWORK_RETRIES = 4
        private val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
    }

    /** Maximum quality for the current network (Wi-Fi or mobile setting). */
    @Volatile
    var quality: dev.owndemand.app.domain.VideoQuality = dev.owndemand.app.domain.VideoQuality.Original
        private set

    /**
     * Applies a new cap (setting changed, or Wi-Fi <-> mobile data). The
     * playing video switches streams at the same position, but only when
     * the cap actually changes what is played.
     */
    fun setQuality(newQuality: dev.owndemand.app.domain.VideoQuality) {
        if (newQuality == quality) return
        val old = quality
        quality = newQuality
        val item = _currentItem.value ?: return
        if (player.currentMediaItem == null) return
        val repo = repository
        if (repo.streamUri(item, old) != repo.streamUri(item, newQuality)) onAddressChanged()
    }

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
                        // A user pause (playWhenReady off while ready) saves
                        // the position now rather than up to one timeline
                        // interval later. Item switches keep playWhenReady on,
                        // so they never report the new item at position 0.
                        if (!isPlaying && !playWhenReady && playbackState == Player.STATE_READY) {
                            report("paused")
                        }
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
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                _tracks.value = tracks
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }.forEach { g ->
                    for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                        val f = g.getTrackFormat(i)
                        android.util.Log.i(
                            "OwnDemand",
                            "video track ${f.width}x${f.height} ${f.sampleMimeType ?: f.codecs} " +
                                "${f.bitrate / 1000} kbps, media ${player.currentMediaItem?.localConfiguration?.mimeType ?: "progressive"}",
                        )
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) finishCurrent()
            }
        })
    }

    val currentRatingKey: String? get() = _currentItem.value?.ratingKey

    private val _endedKey = MutableStateFlow<String?>(null)

    /** The video that just played to its end (cleared when anything plays). */
    val endedKey: StateFlow<String?> = _endedKey.asStateFlow()

    /** Server-side conversion currently feeding the player, if any. */
    private var transcodeSession: String? = null

    /** The ratingKey whose end-of-video handling already ran (once per playback). */
    private var endHandledKey: String? = null

    fun play(item: ItemDto) {
        // Switching away from another video: save where it was left.
        _currentItem.value?.let { previous ->
            if (previous.ratingKey != item.ratingKey) settleAfter(previous.ratingKey, report("stopped"))
        }
        endHandledKey = null
        _endedKey.value = null
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
        val subtitles = repository.subtitleSidecars(item)
        _hasSubtitles.value = subtitles.isNotEmpty()
        player.setPlaybackSpeed(_speed.value)
        // One transcode session per start; the previous one is stopped.
        transcodeSession?.let { old -> scope.launch { repository.stopTranscode(old) } }
        val session = java.util.UUID.randomUUID().toString()
        val (uri, mime) = repository.streamUri(item, quality, session)
        transcodeSession = if (mime != null) session else null
        android.util.Log.i("OwnDemand", "stream: ${if (mime == null) "direct" else "transcode"}, cap ${quality.label}")
        val builder = MediaItem.Builder()
            .setUri(uri)
            .apply { mime?.let { setMimeType(it) } }
            .setMediaId(item.ratingKey)
        // Every subtitle file the video has (Chunk 3), so the CC menu can
        // offer each language; the preferred one is picked automatically.
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(sub.language ?: "en")
                        .setLabel(sub.label)
                        .build()
                },
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

    private val _tracks = MutableStateFlow(androidx.media3.common.Tracks.EMPTY)

    /** The current video's tracks (for the subtitle / audio menu). */
    val tracks: StateFlow<androidx.media3.common.Tracks> = _tracks.asStateFlow()

    /** Subtitle language carried over to the next video (menu choice). */
    private var preferredTextLanguage: String = "en"

    /** Subtitle menu: a specific track, or null for off. The language sticks. */
    fun selectSubtitle(option: TrackOption?) {
        if (option == null) {
            _captionsEnabled.value = false
            applyCaptions()
            return
        }
        _captionsEnabled.value = true
        option.language?.let { preferredTextLanguage = it }
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex),
            )
            .build()
    }

    /** Audio menu: plays the chosen audio track of this video. */
    fun selectAudio(option: TrackOption) {
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex),
            )
            .build()
    }

    private fun applyCaptions() {
        // The sidecar has no Plex "default" flag, so enabling the TEXT
        // type alone is not enough — steer selection with the language.
        // A menu choice for a previous video does not carry over as a
        // track override, only as the preferred language.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !_captionsEnabled.value)
            .setPreferredTextLanguage(if (_captionsEnabled.value) preferredTextLanguage else null)
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
            if (recoverAddress()) {
                // Another address answered: rebuild the stream URLs from it.
                onAddressChanged(position)
                return@launch
            }
            player.seekTo(position)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * The video reached its end: a final report at full length, then an
     * explicit "watched" (Plex otherwise relies on its 90 % threshold and the
     * last periodic report can land several seconds before the end).
     */
    private fun finishCurrent() {
        val item = _currentItem.value ?: return
        if (endHandledKey == item.ratingKey) return
        endHandledKey = item.ratingKey
        _endedKey.value = item.ratingKey
        val duration = if (player.duration > 0) player.duration else item.duration
        val reported = report("stopped", timeMs = duration)
        scope.launch {
            reported?.join()
            repository.scrobble(item.ratingKey)
            onItemSettled(item.ratingKey)
        }
    }

    private fun settleAfter(ratingKey: String, reported: Job?) {
        if (endHandledKey == ratingKey) return // finishCurrent already settled it
        scope.launch {
            reported?.join()
            onItemSettled(ratingKey)
        }
    }

    /**
     * The server's address changed: restart the current video from the new
     * address at the same position, keeping play/pause as it was.
     */
    fun onAddressChanged(positionMs: Long = player.currentPosition) {
        val item = _currentItem.value ?: return
        if (player.currentMediaItem == null) return
        val wasPlaying = player.playWhenReady
        play(item.copy(viewOffset = positionMs.coerceAtLeast(0)))
        player.playWhenReady = wasPlaying
    }

    /** Stop playback and report the position for resume. */
    fun stopAndReport() {
        _currentItem.value?.let { settleAfter(it.ratingKey, report("stopped")) }
        timelineJob?.cancel()
        retryJob?.cancel()
        transcodeSession?.let { old -> scope.launch { repository.stopTranscode(old) } }
        transcodeSession = null
        _endedKey.value = null
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

    /**
     * Reports [_currentItem]'s state. Positions are only read while the
     * player really holds that item (media id = ratingKey), so a position
     * can never be attributed to the wrong video mid-switch.
     */
    private fun report(state: String, timeMs: Long? = null): Job? {
        val item = _currentItem.value ?: return null
        val ratingKey = item.ratingKey
        if (player.currentMediaItem?.mediaId != ratingKey) return null
        val time = (timeMs ?: player.currentPosition).coerceAtLeast(0)
        val duration = if (player.duration > 0) player.duration else item.duration
        return scope.launch {
            repository.reportTimeline(ratingKey, state, time, duration)
        }
    }
}
