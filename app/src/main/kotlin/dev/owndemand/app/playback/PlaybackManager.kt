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
    /** The library in Up-next order (newest first), for "next" (Chunk 4). */
    private val nextCandidates: () -> List<ItemDto> = { emptyList() },
) {
    private val repository: PlexRepository get() = repositoryProvider()

    companion object {
        private const val TIMELINE_INTERVAL_MS = 10_000L

        /** Scrub-preview bucket: BIF keyframes are ~10 s apart. */
        private const val PREVIEW_BUCKET_MS = 5_000L

        /** Buffering this long with data loaded ahead counts as a decoder stall. */
        private const val STALL_CHECK_MS = 3_000L
        private const val STALL_BUFFERED_AHEAD_MS = 3_000L

        /** Network-drop recovery attempts and backoff (M6). */
        private const val MAX_NETWORK_RETRIES = 4
        private val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
    }

    private val _settingsQuality = MutableStateFlow(dev.owndemand.app.domain.VideoQuality.Original)

    /** The Settings cap for the current network (Wi-Fi or mobile data). */
    val settingsQuality: StateFlow<dev.owndemand.app.domain.VideoQuality> = _settingsQuality.asStateFlow()

    private val _qualityOverride = MutableStateFlow<dev.owndemand.app.domain.VideoQuality?>(null)

    /**
     * The in-player quality choice for the current video; null = "Auto"
     * (follow Settings). Cleared whenever a different video starts.
     */
    val qualityOverride: StateFlow<dev.owndemand.app.domain.VideoQuality?> = _qualityOverride.asStateFlow()

    /** The cap in force now: the in-player choice, else the Settings cap. */
    val quality: dev.owndemand.app.domain.VideoQuality
        get() = effectiveQuality(_qualityOverride.value, _settingsQuality.value)

    /** The app is in the background or picture-in-picture (Chunk 4). */
    private var background = false

    /**
     * Leaving / returning to the app or picture-in-picture. The stream keeps
     * its quality (a 480p drop was tried and removed 2026-10-19: the switch
     * lagged visibly in PiP and left a gap in background audio).
     */
    fun setBackgroundMode(on: Boolean) {
        if (background == on) return
        background = on
        // Left the app while the autoplay countdown was showing: play the
        // next video now, as autoplay does in the background.
        if (on && autoplayEnabled && _endedKey.value != null && _endedKey.value == currentRatingKey) {
            skipToNext()
        }
    }

    /** The Autoplay setting; in the background the next video plays at once. */
    @Volatile
    var autoplayEnabled: Boolean = true

    // ---- Skip segments (Chunk 10) ----

    /**
     * The SponsorBlock client; only ever asked while
     * [segmentSettings].enabled is on (AppContainer wires it).
     */
    @Volatile
    var segmentClient: dev.owndemand.app.data.segments.SegmentClient? = null

    /** The Skip segments setting, pushed from Settings. */
    @Volatile
    private var segmentSettings: SegmentSettings = SegmentSettings()

    /**
     * Apply a changed Skip segments setting to the video that is playing:
     * switching on looks it up now, a category change redraws the marks and
     * redoes the schedule, switching off drops everything loaded.
     */
    fun applySegmentSettings(settings: SegmentSettings) {
        if (settings == segmentSettings) return
        segmentSettings = settings
        if (!settings.enabled) {
            clearSkipState()
            return
        }
        val item = _currentItem.value ?: return
        if (segmentsFetchedFor != null) refreshSkipSegments(item) else maybeFetchSegments(item)
    }

    private val _skipSegments = MutableStateFlow<List<SkipSegment>>(emptyList())

    /** The current video's segments (enabled categories only), for the scrubber. */
    val skipSegments: StateFlow<List<SkipSegment>> = _skipSegments.asStateFlow()

    private val _skipNotice = MutableStateFlow<SkipNotice?>(null)

    /**
     * "Skipped sponsor · Undo", set after an automatic jump for
     * [SKIP_NOTICE_MS] — timed here, not by the chip, so a skip in the
     * background or picture-in-picture never offers a stale Undo later.
     */
    val skipNotice: StateFlow<SkipNotice?> = _skipNotice.asStateFlow()

    private var skipNoticeJob: Job? = null

    /** Every category of the current video, as looked up. */
    private var fetchedSegments: List<SkipSegment> = emptyList()

    /** Segments Undo brought back: this viewing leaves them alone. */
    private val undoneSkips = mutableSetOf<SkipSegment>()

    /** Source id already looked up this video (quality / address restarts don't re-ask). */
    private var segmentsFetchedFor: String? = null

    private var segmentJob: Job? = null

    private fun clearSkipState() {
        segmentJob?.cancel()
        segmentJob = null
        cancelSkipMessages()
        skipNoticeJob?.cancel()
        _skipSegments.value = emptyList()
        _skipNotice.value = null
        fetchedSegments = emptyList()
        undoneSkips.clear()
        segmentsFetchedFor = null
    }

    /**
     * Look segments up once per video, in parallel with the stream (no id
     * in the file name, no lookup; any failure, no segments). Then schedule
     * the jump-over segments — one player message each, so there is no
     * polling loop.
     */
    private fun maybeFetchSegments(item: ItemDto) {
        val videoId = segmentLookupFor(item, segmentSettings) ?: return
        if (segmentsFetchedFor == videoId) {
            // Same video restarting (quality / address change): the new
            // media item dropped the old messages; schedule them again.
            refreshSkipSegments(item)
            return
        }
        val client = segmentClient ?: return
        val forKey = item.ratingKey
        segmentJob?.cancel()
        segmentJob = scope.launch {
            val fetched = runCatching { client.lookup(videoId) }
                .getOrElse {
                    android.util.Log.w("OwnDemand", "segment lookup failed", it)
                    emptyList()
                }
            // Still the same video, and still wanted?
            if (_currentItem.value?.ratingKey != forKey || !segmentSettings.enabled) return@launch
            segmentsFetchedFor = videoId
            fetchedSegments = fetched
            refreshSkipSegments(item)
            android.util.Log.i(
                "OwnDemand",
                "skip segments: ${fetched.size} found, ${_skipSegments.value.size} enabled",
            )
        }
    }

    /** Marks and schedule from what was looked up, under the current modes. */
    private fun refreshSkipSegments(item: ItemDto) {
        val duration = if (player.duration > 0) player.duration else item.duration
        _skipSegments.value = visibleSegments(fetchedSegments, segmentSettings.modes, duration)
        scheduleAutoSkips()
    }

    /** Deliver each jump-over segment at its start, while its video plays. */
    private val skipMessages = mutableListOf<androidx.media3.exoplayer.PlayerMessage>()

    private fun cancelSkipMessages() {
        skipMessages.forEach { runCatching { it.cancel() } }
        skipMessages.clear()
    }

    private fun scheduleAutoSkips() {
        cancelSkipMessages()
        autoSkipTargets(_skipSegments.value, segmentSettings.modes, undoneSkips).forEach { seg ->
            skipMessages += player.createMessage { _, _ -> autoSkipNow(seg) }
                .setPosition(seg.startMs)
                // Kept after delivery: a seek back before the segment
                // brings it round again, and it is skipped again.
                .setDeleteAfterDelivery(false)
                .setLooper(android.os.Looper.getMainLooper())
                .send()
        }
    }

    /** Playback reached a jump-over segment's start: jump it, offer Undo. */
    private fun autoSkipNow(seg: SkipSegment) {
        if (seg in undoneSkips || seg !in _skipSegments.value) return
        if (staleAutoSkip(seg, player.currentPosition)) return
        player.seekTo(seg.endMs)
        val notice = SkipNotice(seg)
        _skipNotice.value = notice
        skipNoticeJob?.cancel()
        skipNoticeJob = scope.launch {
            delay(SKIP_NOTICE_MS)
            if (_skipNotice.value == notice) _skipNotice.value = null
        }
        android.util.Log.i(
            "OwnDemand",
            "skipped ${seg.category.wire} ${(seg.endMs - seg.startMs) / 1000}s",
        )
    }

    /** The segment with a live "Skip …" button now (see [skipButtonFor]). */
    fun skipButtonTarget(positionMs: Long): SkipSegment? =
        skipButtonFor(_skipSegments.value, segmentSettings.modes, positionMs)

    /** The "Skip …" button: jump to the segment's end. */
    fun skipNow(seg: SkipSegment) {
        player.seekTo(seg.endMs)
    }

    /** "Undo": jump back into the skipped stretch and leave it alone this viewing. */
    fun undoSkip() {
        val notice = _skipNotice.value ?: return
        _skipNotice.value = null
        skipNoticeJob?.cancel()
        undoneSkips += notice.segment
        player.seekTo(notice.restoreMs)
    }

    /** Settings cap changed (setting edited, or Wi-Fi <-> mobile data). */
    fun setQuality(newQuality: dev.owndemand.app.domain.VideoQuality) {
        val before = quality
        _settingsQuality.value = newQuality
        switchStreamIfNeeded(before)
    }

    /** Player quality menu: a cap for this video only, or null for Auto. */
    fun setQualityOverride(choice: dev.owndemand.app.domain.VideoQuality?) {
        val before = quality
        _qualityOverride.value = choice
        switchStreamIfNeeded(before)
    }

    /**
     * Restarts the playing video at the same position when the cap in force
     * changed what is played (direct file vs conversion, or another cap).
     */
    private fun switchStreamIfNeeded(before: dev.owndemand.app.domain.VideoQuality) {
        val now = quality
        if (now == before) return
        val item = _currentItem.value ?: return
        if (player.currentMediaItem == null) return
        val repo = repository
        if (repo.streamUri(item, before) != repo.streamUri(item, now)) onAddressChanged()
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
            // Background playback (Chunk 4): keep Wi-Fi and the CPU awake
            // while playing, pause for calls / other apps' audio, and pause
            // when headphones are unplugged.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        _wantsToPlay.value = playWhenReady && _currentItem.value != null
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            _videoAspect.value =
                                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        }
                    }

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

    /** A video is loaded and set to play (also while buffering): PiP-worthy. */
    private val _wantsToPlay = MutableStateFlow(false)
    val wantsToPlay: StateFlow<Boolean> = _wantsToPlay.asStateFlow()

    /** Width / height of the playing video (picture-in-picture window shape). */
    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    /** Videos played before the current one, oldest first ("previous"). */
    private var history: List<String> = emptyList()

    private val _queue = MutableStateFlow<PlayQueue?>(null)

    /**
     * The channel being played (Chunk 8), or null. While set it replaces
     * Up next for autoplay and every "next" button; it ends when a video
     * outside it plays or the watch screen's × is tapped.
     */
    val queue: StateFlow<PlayQueue?> = _queue.asStateFlow()

    /** Starts playing a channel; the caller then opens its first video. */
    fun startQueue(queue: PlayQueue) {
        _queue.value = queue
    }

    fun clearQueue() {
        _queue.value = null
    }

    /** A video's watched state / resume offset changed: keep the queue's copy fresh. */
    fun patchQueueItem(fresh: ItemDto) {
        val q = _queue.value ?: return
        if (fresh.ratingKey !in q.keys) return
        _queue.value = q.copy(items = q.items.map { if (it.ratingKey == fresh.ratingKey) fresh else it })
    }

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
    private var stallJob: Job? = null

    /**
     * Stuck "buffering" although plenty is loaded: the decoder did not pick
     * up after its surface changed (seen on the emulator when the video
     * moved to a new surface while a stream started). A seek in place
     * flushes the decoder and playback continues.
     */
    private suspend fun recoverIfStalled() {
        delay(STALL_CHECK_MS)
        if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
        if (player.bufferedPosition - player.currentPosition < STALL_BUFFERED_AHEAD_MS) return
        android.util.Log.i("OwnDemand", "playback stalled with data loaded; re-seeking")
        player.seekTo(player.currentPosition)
    }
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
                stallJob?.cancel()
                if (playbackState == Player.STATE_BUFFERING) stallJob = scope.launch { recoverIfStalled() }
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
        // Playing a video outside the channel being played ends that queue.
        _queue.value?.let { if (item.ratingKey !in it.keys) _queue.value = null }
        // The in-player quality choice is per video: a different video
        // starts on the Settings cap again. Segments belong to the video,
        // not the restart: a different video clears them, the same video
        // (address / quality change) keeps them.
        if (_currentItem.value?.ratingKey != item.ratingKey) {
            _qualityOverride.value = null
            clearSkipState()
        }
        // Switching away from another video: save where it was left, and
        // remember it for "previous" (unless previous is what brought us here).
        _currentItem.value?.let { previous ->
            if (previous.ratingKey != item.ratingKey) {
                settleAfter(previous.ratingKey, report("stopped"))
                if (recordHistory) history = pushHistory(history, previous.ratingKey)
            }
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
            .setMediaMetadata(sessionMetadata(item))
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
        _wantsToPlay.value = true
        report("buffering")
        maybeFetchSegments(item)
    }

    /** False only while "previous" plays a video from the history. */
    private var recordHistory = true

    /**
     * Notification / lock screen / headset / PiP "next": the next video of
     * the channel being played, else the next Up-next video.
     */
    fun skipToNext() {
        peekNextKey()?.let { playByKey(it, fromHistory = false) }
    }

    /**
     * What "next" would play: the channel's next video (null after its last
     * one — once through), else the next Up-next video. Null = the player's
     * next button is greyed.
     */
    fun peekNextKey(): String? {
        val current = _currentItem.value ?: return null
        _queue.value?.let { queue -> return queueNextKey(queue, current.ratingKey) }
        val played = history.toSet() + current.ratingKey
        return pickNextVideo(nextCandidates(), current.ratingKey, played)?.ratingKey
    }

    /** Whether "previous" would do anything at [positionMs] (player button state). */
    fun hasPrevious(positionMs: Long): Boolean {
        val currentKey = _currentItem.value?.ratingKey ?: return false
        return previousAvailable(positionMs, history.filter { it != currentKey })
    }

    /** "Previous": the video before this one, or the start of this one. */
    fun skipToPrevious() {
        val currentKey = _currentItem.value?.ratingKey ?: return
        history = history.filter { it != currentKey }
        when (val action = previousAction(player.currentPosition, history)) {
            PreviousAction.Restart -> {
                // Starting over counts as a new viewing (end handling runs again).
                endHandledKey = null
                _endedKey.value = null
                seekTo(0)
            }
            is PreviousAction.PlayPrevious -> {
                history = history.dropLast(1)
                playByKey(action.ratingKey, fromHistory = true)
            }
        }
    }

    /** Plays a video by key with its full details (resume offset, subtitles). */
    private fun playByKey(ratingKey: String, fromHistory: Boolean) {
        scope.launch {
            val detail = runCatching { repository.item(ratingKey) }
                .onFailure { android.util.Log.w("OwnDemand", "next/previous: could not load the video", it) }
                .getOrNull() ?: return@launch
            recordHistory = !fromHistory
            try {
                play(detail)
            } finally {
                recordHistory = true
            }
        }
    }

    /**
     * Title, channel and artwork for the notification and lock screen. The
     * artwork is an app-private URI the session's bitmap loader resolves, so
     * no server address or token ever leaves the app in media metadata.
     */
    private fun sessionMetadata(item: ItemDto): androidx.media3.common.MediaMetadata =
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(dev.owndemand.app.ui.common.displayTitle(item.title, item.channelName))
            .setArtist(item.channelName)
            .setDisplayTitle(dev.owndemand.app.ui.common.displayTitle(item.title, item.channelName))
            .setSubtitle(item.channelName)
            .apply { if (item.thumb.isNotBlank()) setArtworkUri(artworkUri(item.thumb)) }
            .build()

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
        // In the background / picture-in-picture there is no countdown: the
        // next video starts at once (user choice 2026-10-19).
        if (background && autoplayEnabled) skipToNext()
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
        _wantsToPlay.value = false
        _reconnecting.value = false
        previewPartId = null
        _bifIndex.value = null
        clearSkipState()
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

/** The cap in force: an in-player choice wins over the Settings cap. */
fun effectiveQuality(
    override: dev.owndemand.app.domain.VideoQuality?,
    settings: dev.owndemand.app.domain.VideoQuality,
): dev.owndemand.app.domain.VideoQuality = override ?: settings
