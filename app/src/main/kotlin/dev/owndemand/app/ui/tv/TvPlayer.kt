package dev.owndemand.app.ui.tv

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.owndemand.app.AppContainer
import dev.owndemand.app.R
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.domain.VideoQuality
import dev.owndemand.app.playback.PlaybackManager
import dev.owndemand.app.playback.SkipSegment
import dev.owndemand.app.playback.chapterIndexAt
import dev.owndemand.app.playback.chaptersOf
import dev.owndemand.app.playback.trackOptions
import dev.owndemand.app.ui.common.displayTitle
import dev.owndemand.app.ui.common.formatDuration
import dev.owndemand.app.ui.common.formatUploadDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The side or bottom panel open over the video. */
private enum class Panel { None, Subtitles, Audio, Quality, Speed, Chapters, Info }

/** Seconds the autoplay countdown waits (as on phones). */
private const val TV_AUTOPLAY_SECONDS = 5

/**
 * TV player (Chunk 6): full screen, remote first.
 *
 * Controls hidden — OK plays / pauses (or skips / undoes a segment while
 * one is offered), Left / Right seek (held: faster), Up / Down show the
 * controls. Media keys always work. Controls shown — the progress bar
 * scrubs with Left / Right (preview frames above it); buttons below for
 * previous, back 10 s, play / pause, forward 10 s, next, subtitles, audio,
 * quality, speed, chapters and info. Back closes a panel, then hides the
 * controls, then leaves (playback stops and the position is saved).
 */
@Composable
fun TvPlayerScreen(
    container: AppContainer,
    request: TvPlayRequest,
    onPlay: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onExit: () -> Unit,
) {
    val manager = remember { container.playbackManager() }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        loadError = null
        val same = manager.currentRatingKey == request.ratingKey && manager.player.currentMediaItem != null
        when {
            same && request.fromStart -> {
                manager.seekTo(0)
                manager.player.playWhenReady = true
            }
            same -> manager.player.playWhenReady = true
            else -> try {
                val detail = container.repository().item(request.ratingKey)
                manager.play(if (request.fromStart) detail.copy(viewOffset = 0) else detail)
            } catch (e: Exception) {
                loadError = e.localizedMessage ?: "Could not load this video."
            }
        }
    }

    val current by manager.currentItem.collectAsStateWithLifecycle()
    val isPlaying by manager.isPlaying.collectAsStateWithLifecycle()
    val wantsToPlay by manager.wantsToPlay.collectAsStateWithLifecycle()
    val buffering by manager.isBuffering.collectAsStateWithLifecycle()
    val playerError by manager.error.collectAsStateWithLifecycle()
    val reconnecting by manager.reconnecting.collectAsStateWithLifecycle()
    val keepScreenOn by manager.keepScreenOn.collectAsStateWithLifecycle()
    val endedKey by manager.endedKey.collectAsStateWithLifecycle()
    val notice by manager.skipNotice.collectAsStateWithLifecycle()
    val segments by manager.skipSegments.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = manager.player.currentPosition.coerceAtLeast(0)
            durationMs = manager.player.duration.takeIf { it > 0 } ?: (current?.duration ?: 0L)
            bufferedMs = manager.player.bufferedPosition.coerceAtLeast(0)
            delay(250)
        }
    }

    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.None) }
    var interaction by remember { mutableLongStateOf(0L) }
    fun touch() {
        interaction = System.currentTimeMillis()
    }
    LaunchedEffect(controls, interaction, isPlaying, panel) {
        if (controls && isPlaying && panel == Panel.None) {
            delay(TV_CONTROLS_HIDE_MS)
            controls = false
        }
    }

    // Scrubbing: presses move a pending position; the player seeks once
    // they pause (one seek per burst, not one per press).
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var seekRepeat by remember { mutableIntStateOf(0) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    fun scrub(direction: Int) {
        val now = System.currentTimeMillis()
        seekRepeat = if (now - lastSeekAt < TV_SEEK_REPEAT_WINDOW_MS) seekRepeat + 1 else 0
        lastSeekAt = now
        pendingSeek = tvSeekTarget(pendingSeek ?: positionMs, direction * tvSeekStepMs(seekRepeat), durationMs)
        touch()
    }
    LaunchedEffect(pendingSeek, lastSeekAt) {
        val target = pendingSeek ?: return@LaunchedEffect
        delay(TV_SEEK_COMMIT_MS)
        manager.seekTo(target)
        positionMs = target
        pendingSeek = null
    }

    // Autoplay (as on phones): at the end, count down, then the next video.
    val ended = current != null && endedKey == current?.ratingKey
    val nextKey = remember(ended, current?.ratingKey) { if (ended) manager.peekNextKey() else null }
    var cancelledFor by remember { mutableStateOf<String?>(null) }
    val counting = ended && settings?.autoplay == true && nextKey != null && cancelledFor != endedKey
    var secondsLeft by remember { mutableIntStateOf(TV_AUTOPLAY_SECONDS) }
    LaunchedEffect(counting, nextKey) {
        if (!counting || nextKey == null) return@LaunchedEffect
        secondsLeft = TV_AUTOPLAY_SECONDS
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
        onPlay(nextKey)
    }
    LaunchedEffect(ended) { if (ended) controls = false }

    val skipTarget = if (pendingSeek == null) manager.skipButtonTarget(positionMs) else null

    val root = remember { FocusRequester() }
    val playPause = remember { FocusRequester() }
    val seekBar = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { root.requestFocus() }
    }
    LaunchedEffect(controls, panel, ended) {
        if (!controls && panel == Panel.None && !ended) runCatching { root.requestFocus() }
    }
    // A new video opens with its controls up: focus Play / Pause.
    LaunchedEffect(current?.ratingKey) {
        if (current == null || !controls) return@LaunchedEffect
        delay(120)
        runCatching { playPause.requestFocus() }
    }

    fun leave() {
        manager.stopAndReport()
        onExit()
    }

    BackHandler {
        when {
            panel != Panel.None -> {
                panel = Panel.None
                controls = true
                touch()
                scope.launch {
                    delay(30)
                    runCatching { playPause.requestFocus() }
                }
            }
            controls && !ended -> controls = false
            else -> leave()
        }
    }

    fun showControls(focus: FocusRequester) {
        controls = true
        touch()
        scope.launch {
            delay(30)
            runCatching { focus.requestFocus() }
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        // Media keys: always, whatever has focus.
        when (e.key) {
            Key.MediaPlayPause -> { manager.togglePlayPause(); showControls(playPause); return true }
            Key.MediaPlay -> { manager.player.play(); return true }
            Key.MediaPause -> { manager.player.pause(); showControls(playPause); return true }
            Key.MediaFastForward -> { scrub(+1); controls = true; return true }
            Key.MediaRewind -> { scrub(-1); controls = true; return true }
            Key.MediaNext -> { manager.peekNextKey()?.let(onPlay); return true }
            Key.MediaPrevious -> { manager.skipToPrevious(); return true }
        }
        if (panel != Panel.None || ended) {
            touch()
            return false
        }
        if (controls) {
            touch()
            return false
        }
        // Controls hidden: the whole remote drives playback.
        return when (e.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                when {
                    notice != null -> manager.undoSkip()
                    skipTarget != null -> manager.skipNow(skipTarget)
                    else -> {
                        manager.togglePlayPause()
                        if (manager.player.playWhenReady.not()) showControls(playPause)
                    }
                }
                true
            }
            Key.DirectionLeft -> { scrub(-1); showControls(seekBar); true }
            Key.DirectionRight -> { scrub(+1); showControls(seekBar); true }
            Key.DirectionUp, Key.DirectionDown -> { showControls(playPause); true }
            Key.Menu -> { panel = Panel.Info; controls = true; true }
            else -> false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Focus stays in the player: the browse screens underneath are
            // never reached from here.
            .focusProperties { this.onExit = { cancelFocusChange() } }
            .focusRequester(root)
            .onPreviewKeyEvent(::onKey)
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.player_surface_pip, null, false) as PlayerView
            },
            update = {
                it.player = manager.player
                it.keepScreenOn = keepScreenOn
            },
            onRelease = { it.player = null },
        )

        if (buffering && !ended) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)
        }

        val item = current
        AnimatedVisibility(
            visible = controls && panel != Panel.Chapters && item != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (item != null) Box(Modifier.fillMaxSize()) {
                Controls(
                    item = item,
                    manager = manager,
                    positionMs = positionMs,
                    pendingSeekMs = pendingSeek,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    segments = segments,
                    isPlaying = wantsToPlay,
                    skipTarget = skipTarget,
                    playPause = playPause,
                    seekBar = seekBar,
                    onScrub = ::scrub,
                    onCommitSeek = {
                        pendingSeek?.let { manager.seekTo(it); positionMs = it }
                        pendingSeek = null
                    },
                    onPlay = onPlay,
                    onPanel = { panel = it },
                    artwork = { container.repository().artworkUrl(it) },
                )
            }
        }

        // Skip button / undo while the controls are hidden.
        if (!controls && panel == Panel.None && !ended) {
            val n = notice
            when {
                n != null -> Hint("Skipped ${n.label} · OK to undo", Modifier.align(Alignment.BottomStart))
                skipTarget != null -> Hint("Skip ${skipTarget.category.label} · OK", Modifier.align(Alignment.BottomEnd))
            }
        }

        if (reconnecting) {
            Hint("Reconnecting…", Modifier.align(Alignment.TopEnd))
        }

        when (panel) {
            Panel.None -> Unit
            Panel.Chapters -> ChaptersPanel(item, positionMs, manager, artwork = { container.repository().artworkUrl(it) }) {
                panel = Panel.None
                controls = true
                touch()
            }
            Panel.Info -> item?.let { InfoPanel(it, onOpenChannel) { panel = Panel.None } }
            else -> ChoicesPanel(panel, manager) { panel = Panel.None }
        }

        val err = loadError ?: playerError
        if (err != null && !ended) {
            ErrorOverlay(
                message = err,
                onRetry = {
                    if (loadError != null) {
                        loadError = null
                        scope.launch {
                            runCatching { container.repository().item(request.ratingKey) }
                                .onSuccess { manager.play(it) }
                                .onFailure { loadError = it.localizedMessage ?: "Could not load this video." }
                        }
                    } else {
                        manager.onAddressChanged()
                    }
                },
                onBack = ::leave,
            )
        }

        if (ended && item != null) {
            EndOverlay(
                counting = counting,
                secondsLeft = secondsLeft,
                next = nextKey?.let { key -> container.libraryIndex.items.value?.firstOrNull { it.ratingKey == key } },
                artwork = { container.repository().artworkUrl(it) },
                onPlayNext = { nextKey?.let(onPlay) },
                onCancel = { cancelledFor = endedKey },
                onReplay = {
                    cancelledFor = endedKey
                    manager.seekTo(0)
                    manager.player.play()
                },
                onBack = ::leave,
            )
        }
    }
}

/** A small dark label over the video (skip, undo, reconnecting). */
@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier
            .padding(horizontal = TvSafeHorizontal, vertical = TvSafeVertical)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun BoxScope.Controls(
    item: ItemDto,
    manager: PlaybackManager,
    positionMs: Long,
    pendingSeekMs: Long?,
    durationMs: Long,
    bufferedMs: Long,
    segments: List<SkipSegment>,
    isPlaying: Boolean,
    skipTarget: SkipSegment?,
    playPause: FocusRequester,
    seekBar: FocusRequester,
    onScrub: (Int) -> Unit,
    onCommitSeek: () -> Unit,
    onPlay: (String) -> Unit,
    onPanel: (Panel) -> Unit,
    artwork: (String) -> String,
) {
    val queue by manager.queue.collectAsStateWithLifecycle()
    val hasSubs by manager.hasSubtitles.collectAsStateWithLifecycle()
    val captionsOn by manager.captionsEnabled.collectAsStateWithLifecycle()
    val tracks by manager.tracks.collectAsStateWithLifecycle()
    val chapters = remember(item.ratingKey) { chaptersOf(item) }
    val audioTracks = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_AUDIO) }

    // Scrims so white text reads over any frame.
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))),
    )

    // Top: what is playing.
    Column(
        Modifier
            .align(Alignment.TopStart)
            .padding(horizontal = TvSafeHorizontal, vertical = TvSafeVertical),
    ) {
        queue?.takeIf { item.ratingKey in it.keys }?.let { q ->
            Text(
                "Playing ${q.channelTitle} · ${q.order.label.lowercase()}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        item.channelName?.let { Text(it, color = Color(0xFFA78BFA), style = MaterialTheme.typography.titleSmall) }
        Text(
            displayTitle(item.title, item.channelName),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(720.dp),
        )
        val chapterIndex = chapterIndexAt(chapters, pendingSeekMs ?: positionMs)
        chapters.getOrNull(chapterIndex)?.let {
            Text(it.title, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
        }
    }

    // Bottom: progress bar and buttons.
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = TvSafeHorizontal, vertical = TvSafeVertical)
            .focusGroup(),
    ) {
        SeekBar(
            manager = manager,
            positionMs = positionMs,
            pendingSeekMs = pendingSeekMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            chapterStarts = chapters.drop(1).map { it.startMs },
            segments = segments,
            focusRequester = seekBar,
            onScrub = onScrub,
            onCommit = onCommitSeek,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val canPrevious = manager.hasPrevious(positionMs)
            val nextKey = manager.peekNextKey()
            PlayerButton(R.drawable.ic_skip_previous, "Previous", enabled = canPrevious) { manager.skipToPrevious() }
            PlayerButton(R.drawable.ic_seek_back, "Back 10 seconds") { manager.seekBy(-TV_SEEK_STEP_MS) }
            PlayerButton(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                size = 64.dp,
                modifier = Modifier.focusRequester(playPause),
            ) { manager.togglePlayPause() }
            PlayerButton(R.drawable.ic_seek_fwd, "Forward 10 seconds") { manager.seekBy(TV_SEEK_STEP_MS) }
            PlayerButton(R.drawable.ic_skip_next, "Next", enabled = nextKey != null) { nextKey?.let(onPlay) }
            skipTarget?.let { target ->
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { manager.skipNow(target) }) { Text("Skip ${target.category.label}") }
            }
            Spacer(Modifier.weight(1f))
            if (hasSubs) {
                PlayerButton(R.drawable.ic_cc, if (captionsOn) "Subtitles: on" else "Subtitles: off", active = captionsOn) {
                    onPanel(Panel.Subtitles)
                }
            }
            if (audioTracks.size > 1) PlayerButton(R.drawable.ic_audio, "Audio track") { onPanel(Panel.Audio) }
            PlayerButton(R.drawable.ic_settings, "Quality") { onPanel(Panel.Quality) }
            PlayerButton(R.drawable.ic_speed, "Speed") { onPanel(Panel.Speed) }
            if (chapters.isNotEmpty()) PlayerButton(R.drawable.ic_chapters, "Chapters") { onPanel(Panel.Chapters) }
            PlayerButton(R.drawable.ic_info, "Info") { onPanel(Panel.Info) }
        }
    }
}

/** A round player button; its label shows above it while focused. */
@Composable
private fun PlayerButton(
    icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(size),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (active) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
                disabledContainerColor = Color.White.copy(alpha = 0.05f),
                disabledContentColor = Color.White.copy(alpha = 0.3f),
            ),
            interactionSource = interaction,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(size * 0.5f))
            }
        }
        if (focused) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .offset(y = -(size / 2 + 22.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The progress bar: played, buffered, chapter gaps and skip segments.
 * Focused, Left / Right scrub (preview frame and time above the thumb), OK
 * seeks at once.
 */
@Composable
private fun SeekBar(
    manager: PlaybackManager,
    positionMs: Long,
    pendingSeekMs: Long?,
    durationMs: Long,
    bufferedMs: Long,
    chapterStarts: List<Long>,
    segments: List<SkipSegment>,
    focusRequester: FocusRequester,
    onScrub: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shown = pendingSeekMs ?: positionMs
    val fraction = if (durationMs > 0) (shown.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (pendingSeekMs != null) 118.dp else 0.dp)) {
            if (pendingSeekMs != null) {
                val frame = remember(pendingSeekMs / 5_000) {
                    manager.previewFrame(pendingSeekMs)?.let { bytes ->
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                    }
                }
                val previewWidth = 176.dp
                val x = (maxWidth * fraction - previewWidth / 2).coerceIn(0.dp, maxWidth - previewWidth)
                Column(Modifier.offset(x = x).width(previewWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (frame != null) {
                        androidx.compose.foundation.Image(
                            bitmap = frame,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black),
                        )
                    } else {
                        Spacer(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    }
                    Text(formatDuration(pendingSeekMs), color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(shown), color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(14.dp))
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { onScrub(-1); true }
                            Key.DirectionRight -> { onScrub(+1); true }
                            Key.DirectionCenter, Key.Enter -> { onCommit(); true }
                            else -> false
                        }
                    }
                    .focusable(),
                contentAlignment = Alignment.CenterStart,
            ) {
                val barHeight = if (focused) 8.dp else 5.dp
                val trackWidth = maxWidth
                Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f)))
                Box(Modifier.fillMaxWidth(bufferedFraction).height(barHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.4f)))
                Box(Modifier.fillMaxWidth(fraction).height(barHeight).clip(RoundedCornerShape(50)).background(Color(0xFFA78BFA)))
                if (durationMs > 0) {
                    segments.forEach { seg ->
                        val start = trackWidth * (seg.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        val width = trackWidth * ((seg.endMs - seg.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
                        Box(Modifier.offset(x = start).width(width).height(barHeight).background(Color(0xFFFFC107).copy(alpha = 0.85f)))
                    }
                    chapterStarts.forEach { start ->
                        val x = trackWidth * (start.toFloat() / durationMs).coerceIn(0f, 1f)
                        Box(Modifier.offset(x = x - 1.dp).width(2.dp).height(barHeight).background(Color.Black))
                    }
                }
                val thumb = if (focused) 18.dp else 0.dp
                if (thumb > 0.dp) {
                    Box(
                        Modifier
                            .offset(x = (trackWidth * fraction - thumb / 2).coerceAtLeast(0.dp))
                            .size(thumb)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                if (durationMs > 0) formatDuration(durationMs) else "--:--",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Subtitles / audio / quality / speed: a list on the right; OK picks. */
@Composable
private fun ChoicesPanel(panel: Panel, manager: PlaybackManager, onClose: () -> Unit) {
    val tracks by manager.tracks.collectAsStateWithLifecycle()
    val captionsOn by manager.captionsEnabled.collectAsStateWithLifecycle()
    val qualityOverride by manager.qualityOverride.collectAsStateWithLifecycle()
    val settingsQuality by manager.settingsQuality.collectAsStateWithLifecycle()
    val speed by manager.speed.collectAsStateWithLifecycle()

    data class Row(val label: String, val selected: Boolean, val onClick: () -> Unit)

    val (title, rows) = when (panel) {
        Panel.Subtitles -> {
            val options = trackOptions(tracks, C.TRACK_TYPE_TEXT)
            "Subtitles" to listOf(Row("Off", !captionsOn) { manager.selectSubtitle(null) }) +
                options.map { o -> Row(o.label, captionsOn && o.selected) { manager.selectSubtitle(o) } }
        }
        Panel.Audio -> "Audio track" to trackOptions(tracks, C.TRACK_TYPE_AUDIO).map { o ->
            Row(o.label, o.selected) { manager.selectAudio(o) }
        }
        Panel.Quality -> "Quality" to listOf(
            Row("Auto (${settingsQuality.label})", qualityOverride == null) { manager.setQualityOverride(null) },
        ) + VideoQuality.entries.map { q -> Row(q.label, qualityOverride == q) { manager.setQualityOverride(q) } }
        Panel.Speed -> "Speed" to TvSpeeds.map { s -> Row(tvSpeedLabel(s), speed == s) { manager.setSpeed(s) } }
        else -> "" to emptyList()
    }
    val selectedRequester = remember(panel) { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .width(360.dp)
                .fillMaxHeight()
                .background(Color(0xF0181818))
                .padding(horizontal = 22.dp, vertical = TvSafeVertical)
                .focusGroup(),
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            val focusIndex = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(rows) { i, row ->
                    TvListRow(
                        title = row.label,
                        value = if (row.selected) "✓" else null,
                        selected = row.selected,
                        onClick = {
                            row.onClick()
                            onClose()
                        },
                        modifier = if (i == focusIndex) Modifier.focusRequester(selectedRequester) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(panel) {
        delay(40)
        runCatching { selectedRequester.requestFocus() }
    }
}

/** Chapters along the bottom: thumbnail, title, start; OK jumps there. */
@Composable
private fun ChaptersPanel(
    item: ItemDto?,
    positionMs: Long,
    manager: PlaybackManager,
    artwork: (String) -> String,
    onClose: () -> Unit,
) {
    val chapters = remember(item?.ratingKey) { chaptersOf(item) }
    val currentIndex = chapterIndexAt(chapters, positionMs).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    val currentRequester = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.92f))))
                .padding(vertical = TvSafeVertical),
        ) {
            Text(
                "Chapters",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = TvSafeHorizontal, bottom = 10.dp),
            )
            PivotScroll(leading = TvSafeHorizontal) {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = TvSafeHorizontal, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(chapters) { i, chapter ->
                        Column(Modifier.width(200.dp)) {
                            androidx.tv.material3.Card(
                                onClick = {
                                    manager.seekTo(chapter.startMs)
                                    onClose()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .then(if (i == currentIndex) Modifier.focusRequester(currentRequester) else Modifier),
                                scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.06f),
                                border = androidx.tv.material3.CardDefaults.border(
                                    focusedBorder = androidx.tv.material3.Border(
                                        androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                                        shape = RoundedCornerShape(10.dp),
                                    ),
                                ),
                                shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(10.dp)),
                            ) {
                                Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2A))) {
                                    if (chapter.thumb.isNotBlank()) {
                                        AsyncImage(
                                            model = artwork(chapter.thumb),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    if (i == currentIndex) {
                                        Text(
                                            "Now",
                                            color = Color.Black,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(Color(0xFFA78BFA))
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(chapter.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatDuration(chapter.startMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { currentRequester.requestFocus() }
    }
}

/** The video's details: channel (with a way there), date, length, description. */
@Composable
private fun InfoPanel(item: ItemDto, onOpenChannel: (String) -> Unit, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .width(520.dp)
                .fillMaxHeight()
                .background(Color(0xF0181818))
                .padding(horizontal = 28.dp, vertical = TvSafeVertical)
                .focusGroup(),
        ) {
            item.channelName?.let { Text(it, color = Color(0xFFA78BFA), style = MaterialTheme.typography.titleSmall) }
            Text(
                displayTitle(item.title, item.channelName),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(formatUploadDate(item), formatDuration(item.duration)).filter { it.isNotBlank() }.joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item.channelName?.let { name ->
                    Button(onClick = { onOpenChannel(name) }, modifier = Modifier.focusRequester(first)) { Text("Go to channel") }
                }
                OutlinedButton(onClick = onClose, modifier = if (item.channelName == null) Modifier.focusRequester(first) else Modifier) {
                    Text("Close")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                item.summary.ifBlank { "No description." },
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(240f) }; scroll.canScrollForward }
                            Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-240f) }; scroll.canScrollBackward }
                            else -> false
                        }
                    }
                    .focusable()
                    .verticalScroll(scroll),
            )
        }
    }
    LaunchedEffect(Unit) {
        delay(40)
        runCatching { first.requestFocus() }
    }
}

/** A playback or loading error: what happened, Retry, Back. */
@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.focusGroup()) {
            Text("Playback stopped", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(retry)) { Text("Retry") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
    LaunchedEffect(message) {
        delay(40)
        runCatching { retry.requestFocus() }
    }
}

/**
 * The end of a video: the autoplay countdown to the next one (Play now /
 * Cancel), or — cancelled, autoplay off or nothing next — Replay / Back.
 */
@Composable
private fun EndOverlay(
    counting: Boolean,
    secondsLeft: Int,
    next: ItemDto?,
    artwork: (String) -> String,
    onPlayNext: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    onBack: () -> Unit,
) {
    val primary = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            Modifier
                .padding(horizontal = TvSafeHorizontal, vertical = TvSafeVertical)
                .width(420.dp)
                .focusGroup(),
        ) {
            if (next != null && counting) {
                Text("Up next in $secondsLeft", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                AsyncImage(
                    model = artwork(next.thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Text(displayTitle(next.title, next.channelName), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                next.channelName?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPlayNext, modifier = Modifier.focusRequester(primary)) { Text("Play now") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Text("Finished", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onReplay, modifier = Modifier.focusRequester(primary)) { Text("Replay") }
                    if (next != null) OutlinedButton(onClick = onPlayNext) { Text("Play next") }
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
            }
        }
    }
    LaunchedEffect(counting) {
        delay(40)
        runCatching { primary.requestFocus() }
    }
}
