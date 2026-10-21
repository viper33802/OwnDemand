package dev.owndemand.app.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.owndemand.app.R
import dev.owndemand.app.playback.PlaybackManager
import dev.owndemand.app.playback.trackOptions
import dev.owndemand.app.ui.common.formatDuration
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Custom-chrome player: video surface + custom chrome (spec
 * docs/PLAYER-UI-SPEC.md §2–3). Gestures: tap toggles controls (auto-hide
 * 3 s), double-tap halves ±10 s with ripple, long-press 2× speed chip,
 * downward drag docks to the miniplayer, scrubber drag with timestamp
 * bubble. Brightness and volume are deliberately not touched — the device
 * owns those (user direction 2026-10-16).
 */
@Composable
fun VideoPlayer(
    manager: PlaybackManager,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onDock: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    /** Opens the chapter list (Chunk 9); null hides the way in. */
    onOpenChapters: (() -> Unit)? = null,
) {
    val player = manager.player
    val isBuffering by manager.isBuffering.collectAsStateWithLifecycle()
    val reconnecting by manager.reconnecting.collectAsStateWithLifecycle()
    val skipSegments by manager.skipSegments.collectAsStateWithLifecycle()

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }
    var speedBoost by remember { mutableStateOf(false) }
    // Current run of ±10 s steps (double-tap, tap-to-continue, ±10 buttons)
    // and whether its bubble is showing.
    var burst by remember { mutableStateOf<SeekBurst?>(null) }
    var bubbleVisible by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableLongStateOf(0L) }
    // Live read of controlsVisible for the gesture closures (they are
    // created once and must see the current value).
    val scrubberLive = rememberUpdatedState(controlsVisible)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Position ticker (paused while scrubbing so the thumb follows the finger).
    LaunchedEffect(scrubbing) {
        while (true) {
            if (!scrubbing) positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0L)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            kotlinx.coroutines.delay(250)
        }
    }

    // Auto-hide controls 3 s after last interaction (never while paused).
    // An open speed / quality / captions menu keeps the controls up.
    var controlMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible, isPlaying, scrubbing, interactionTick, controlMenuOpen) {
        if (controlsVisible && isPlaying && !scrubbing && !controlMenuOpen) {
            kotlinx.coroutines.delay(3_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(burst) {
        if (burst != null) {
            bubbleVisible = true
            kotlinx.coroutines.delay(SEEK_BURST_WINDOW_MS)
            bubbleVisible = false
        }
    }

    /** One (or [steps]) ±10 s seek, accumulated into the bubble's running total. */
    fun seekStep(forward: Boolean, steps: Int = 1) {
        val now = android.os.SystemClock.uptimeMillis()
        var b = burst
        repeat(steps) { b = nextSeekBurst(b, forward, now) }
        burst = b
        manager.seekBy(if (forward) SEEK_STEP_MS * steps else -SEEK_STEP_MS * steps)
        interactionTick = System.currentTimeMillis()
    }

    val latestOnDock by rememberUpdatedState(onDock)
    val latestOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val fullscreenLive = rememberUpdatedState(fullscreen)
    // Long-press 2x boost: remember the user's chosen speed to restore.
    var boostBaseSpeed by remember { mutableStateOf(1f) }
    val setSpeedBoost = rememberUpdatedState<(Boolean) -> Unit> { on ->
        speedBoost = on
        if (on) {
            boostBaseSpeed = manager.speed.value
            manager.setSpeed(2f)
        } else {
            manager.setSpeed(boostBaseSpeed)
        }
        interactionTick = System.currentTimeMillis()
    }

    // Pinch-zoom + pan (spec §3): scale/offset on a graphicsLayer over the
    // TextureView surface. Reset on item change and fullscreen toggle.
    val zoom = remember { ZoomState() }
    val currentKey by manager.currentItem.collectAsStateWithLifecycle()
    LaunchedEffect(currentKey?.ratingKey, fullscreen) { zoom.reset() }

    Box(modifier = modifier.background(Color.Black)) {
        PlayerSurface(
            player = player,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                },
        )

        // Gesture layer: below the controls, above the surface.
        Box(
            Modifier
                .matchParentSize()
                .playerZoomGestures(zoom)
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interactionTick = System.currentTimeMillis()
                        },
                        onDoubleTap = { offset ->
                            if (zoom.scale > 1f) {
                                zoom.reset() // double-tap while zoomed snaps back
                            } else {
                                val forward = offset.x > size.width / 2f
                                // Inside a live burst the tap-to-continue
                                // handler normally takes each tap; a double
                                // tap that still lands here counts as two.
                                val live = burst?.isLive(android.os.SystemClock.uptimeMillis()) == true
                                seekStep(forward, steps = if (live) 2 else 1)
                            }
                            interactionTick = System.currentTimeMillis()
                        },
                    )
                }
                .pointerInput(player) {
                    // Long-press speed boost + downward drag → miniplayer.
                    // Events are consumed only once a mode is chosen, so plain
                    // taps still reach the tap detector. The long-press deadline
                    // is raced as a timeout (a still finger emits no events).
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // While the controls are up, the scrubber band owns
                        // the bottom of the player: presses there never
                        // tap-pause, boost, or dock. With controls hidden the
                        // tap still reaches through to reveal them.
                        if (scrubberLive.value &&
                            down.position.y >= size.height - ScrubBandHeight.toPx()
                        ) {
                            return@awaitEachGesture
                        }
                        // Tap-to-continue: while a seek bubble is up, every
                        // tap on a half seeks 10 s that way at once (the tap
                        // detector would wait out its double-tap timeout).
                        // A tap is consumed here so it never toggles controls.
                        if (burst?.isLive(down.uptimeMillis) == true) {
                            val up = withTimeoutOrNull(400L) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull null
                                    if (!change.pressed) return@withTimeoutOrNull change
                                    val moved = change.position - down.position
                                    if (abs(moved.x) > 18f || abs(moved.y) > 18f) return@withTimeoutOrNull null
                                }
                                @Suppress("UNREACHABLE_CODE") null
                            }
                            if (up != null) {
                                up.consume()
                                seekStep(forward = down.position.x > size.width / 2f)
                                return@awaitEachGesture
                            }
                        }
                        val startX = down.position.x
                        var mode = 0 // 0 tap · 1 dock drag · 2 speed boost · 3 fullscreen swipe
                        var docked = false
                        var expanded = false
                        try {
                            val decided = withTimeoutOrNull(400L) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break // released early → tap
                                    if (change.isConsumed) {
                                        // Zoom/pan owns this drag (scale > 1).
                                        mode = 0
                                        break
                                    }
                                    val dx = change.position.x - startX
                                    val dy = change.position.y - down.position.y
                                    when (classifyPlayerDrag(dx, dy, fullscreenLive.value)) {
                                        // Toward the description area: dock to
                                        // the miniplayer.
                                        PlayerDrag.Dock -> { mode = 1; break }
                                        // Upward on the inline player: fullscreen.
                                        PlayerDrag.Fullscreen -> { mode = 3; break }
                                        // Sideways belongs to the scrubber.
                                        PlayerDrag.Ignore -> { mode = 0; break }
                                        PlayerDrag.Undecided -> Unit
                                    }
                                }
                                true
                            }
                            if (decided == null && mode == 0) {
                                mode = 2
                                setSpeedBoost.value(true)
                            }
                            if (mode != 0) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    when (mode) {
                                        1 -> {
                                            change.consume()
                                            val dy = change.position.y - down.position.y
                                            if (!docked && dy > size.height * DRAG_COMMIT_FRACTION) {
                                                docked = true
                                                latestOnDock()
                                            }
                                        }
                                        3 -> {
                                            change.consume()
                                            val dy = change.position.y - down.position.y
                                            if (!expanded && -dy > size.height * DRAG_COMMIT_FRACTION) {
                                                expanded = true
                                                latestOnToggleFullscreen()
                                            }
                                        }
                                        2 -> change.consume()
                                    }
                                    if (!change.pressed) break
                                }
                            }
                        } finally {
                            if (mode == 2) setSpeedBoost.value(false)
                        }
                    }
                },
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }

            // Network-drop recovery (M6): the manager is auto-retrying.
            if (reconnecting) {
                Text(
                    "Connection lost — reconnecting…",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            burst?.takeIf { bubbleVisible }?.let { b ->
                SeekRipple(
                    forward = b.forward,
                    label = b.label,
                    modifier = Modifier.align(
                        if (b.forward) Alignment.CenterEnd else Alignment.CenterStart,
                    ),
                )
            }

            AnimatedVisibility(
                visible = speedBoost,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            ) {
                SpeedChip()
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            PlayerControls(
                manager = manager,
                title = title,
                onMenuOpenChange = { controlMenuOpen = it },
                isPlaying = isPlaying,
                positionMs = if (scrubbing) scrubPositionMs else positionMs,
                scrubbing = scrubbing,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                fullscreen = fullscreen,
                onTogglePlay = {
                    manager.togglePlayPause()
                    interactionTick = System.currentTimeMillis()
                },
                // ±10 buttons feed the same running total as the taps.
                onScrubStart = { scrubbing = true; scrubPositionMs = positionMs },
                onScrub = { scrubPositionMs = it },
                onScrubEnd = {
                    manager.seekTo(it)
                    scrubbing = false
                    interactionTick = System.currentTimeMillis()
                },
                previewUrlFor = { manager.previewUrl(it) },
                previewFrameFor = { manager.previewFrame(it) },
                skipSegments = skipSegments,
                onOpenChapters = onOpenChapters?.let { open ->
                    {
                        open()
                        interactionTick = System.currentTimeMillis()
                    }
                },
                onToggleFullscreen = {
                    onToggleFullscreen()
                    interactionTick = System.currentTimeMillis()
                },
            )
        }
        // Screen-provided overlay on top of everything (Chunk 3: the
        // autoplay "Up next" countdown), inline and fullscreen alike.
        overlay?.invoke(this)

        // Skip segments (Chunk 10): the "Skip …" button while inside a
        // segment, shown whether or not the controls are up (they hide
        // after 3 s, long before most segments end) …
        if (!scrubbing) {
            manager.skipButtonTarget(positionMs)?.let { target ->
                SkipSegmentButton(target, controlsVisible, fullscreen) { manager.skipNow(target) }
            }
        }
        // … and "Skipped sponsor · Undo" for 5 s after an automatic jump;
        // Undo goes back and leaves the stretch alone.
        SkipUndoChip(manager)
    }
}

/**
 * "Skip self-promotion ›" at the player's bottom right: just above the bar
 * while the controls are up, lower while they are hidden.
 */
@Composable
private fun BoxScope.SkipSegmentButton(
    target: dev.owndemand.app.playback.SkipSegment,
    controlsVisible: Boolean,
    fullscreen: Boolean,
    onSkip: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .then(if (fullscreen) Modifier.navigationBarsPadding() else Modifier)
            .padding(
                end = 12.dp,
                // Clear of the bar (lifted 14 dp more in fullscreen).
                bottom = when {
                    !controlsVisible -> 16.dp
                    fullscreen -> 60.dp
                    else -> 46.dp
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onSkip)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            "Skip ${target.category.label}  ›",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The Undo chip while the manager offers it (5 s after an automatic skip). */
@Composable
private fun BoxScope.SkipUndoChip(manager: PlaybackManager) {
    val chip by manager.skipNotice.collectAsStateWithLifecycle()
    val notice = chip ?: return
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Skipped ${notice.label}", color = Color.White, fontSize = 13.sp)
        IconButton(
            onClick = { manager.undoSkip() },
            modifier = Modifier.size(40.dp),
        ) {
            Text(
                "Undo",
                color = Color(0xFFA78BFA),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Video surface via Media3 PlayerView. TextureView (not SurfaceView) so
 * Compose graphicsLayer zoom/pan transforms apply to the frames (spec §3).
 */
@Composable
private fun PlayerSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            (android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.player_surface, null, false) as PlayerView)
                .apply { useController = false }
        },
        update = { it.player = player },
    )
}

@Composable
private fun PlayerControls(
    manager: PlaybackManager,
    title: String,
    onMenuOpenChange: (Boolean) -> Unit,
    isPlaying: Boolean,
    positionMs: Long,
    scrubbing: Boolean,
    durationMs: Long,
    bufferedMs: Long,
    fullscreen: Boolean,
    onTogglePlay: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    previewUrlFor: ((Long) -> String?)?,
    previewFrameFor: ((Long) -> ByteArray?)?,
    skipSegments: List<dev.owndemand.app.playback.SkipSegment>,
    onOpenChapters: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
) {
    val hasSubtitles by manager.hasSubtitles.collectAsStateWithLifecycle()
    val playingItem by manager.currentItem.collectAsStateWithLifecycle()
    val chapters = remember(playingItem) { dev.owndemand.app.playback.chaptersOf(playingItem) }
    // Where the chapter title sits on screen: the scrubber band underneath
    // owns every touch there (so a drag starting on the title still
    // scrubs), and a plain tap inside these bounds opens the chapter list.
    var chapterTitleBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val captionsOn by manager.captionsEnabled.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        // Full-surface scrim: the whole video darkens while controls show,
        // strongest at the edges where the chrome sits.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.65f),
                        0.35f to Color.Black.copy(alpha = 0.25f),
                        0.65f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Black.copy(alpha = 0.65f),
                    ),
                ),
        )

        // Top scrim + row. The gradient starts at the true top edge (behind
        // the status bar); only the row content is inset by the status bar.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    ),
                ),
        ) {
            Row(
                Modifier.padding(top = 4.dp, start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // No back arrow (user direction 2026-10-16): the bottom
                // menu, system back and the down-drag all leave playback.
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                // Speed picker (M6): shows the current rate, opens the
                // Reference-app rate list.
                SpeedControl(manager, onMenuOpenChange)
                QualityControl(manager, onMenuOpenChange)
                CaptionsControl(manager, hasSubtitles, captionsOn, onMenuOpenChange)
            }
        }

        // Center transport: previous / play-pause / next (Chunk 8, the same
        // as the notification's buttons; ±10 s stays as the double-tap
        // gesture). Shown whenever the controls are — a single tap reveals
        // them, a second tap acts on a button (spec §2). Previous / next
        // grey out when they would do nothing.
        val queue by manager.queue.collectAsStateWithLifecycle()
        val current by manager.currentItem.collectAsStateWithLifecycle()
        // Re-checked when the channel queue or the video changes, and each
        // second (watched states and history move on while it plays).
        val hasNext = remember(queue, current, positionMs / 1_000) { manager.peekNextKey() != null }
        val hasPrevious = manager.hasPrevious(positionMs)
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleControl(R.drawable.ic_skip_previous, "Previous video", 48.dp, 26.dp, enabled = hasPrevious) {
                manager.skipToPrevious()
            }
            Spacer(Modifier.width(28.dp))
            CircleControl(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                64.dp,
                34.dp,
            ) { onTogglePlay() }
            Spacer(Modifier.width(28.dp))
            CircleControl(R.drawable.ic_skip_next, "Next video", 48.dp, 26.dp, enabled = hasNext) {
                manager.skipToNext()
            }
        }

        // Bottom: "played / total" at the lower left, fullscreen at the
        // lower right — both tight against the edge-flush scrubber like the
        // reference. The 42 dp scrubber touch band stays, it just sits
        // behind this row instead of below it. In fullscreen the whole
        // stack lifts above the gesture pill and off the screen edge
        // (user feedback 2026-10-16); inline stays flush with the video.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Fullscreen hides the system bars (insets 0), so the lift
                // is explicit: clear of the gesture pill and the screen
                // edge; inline stays flush with the video.
                .then(
                    if (fullscreen) {
                        Modifier.navigationBarsPadding().padding(bottom = 14.dp)
                    } else {
                        Modifier
                    },
                )
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                ),
        ) {
            Scrubber(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                scrubbing = scrubbing,
                onScrubStart = onScrubStart,
                onScrub = onScrub,
                onScrubEnd = onScrubEnd,
                previewUrlFor = previewUrlFor,
                previewFrameFor = previewFrameFor,
                chapters = chapters,
                skipSegments = skipSegments,
                onTapInWindow = { tap ->
                    val bounds = chapterTitleBounds
                    if (onOpenChapters != null && bounds != null && bounds.inflate(24f).contains(tap)) {
                        onOpenChapters()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp)
                    // Sit the text just above the bar. T4 fix (survey A1):
                    // the row used to offset 8 dp down so the button's
                    // touch area spilled past the video edge — fine on a
                    // phone (it landed in the card's dead padding) but on
                    // tablet landscape the video ends at the nav bar and
                    // the fullscreen button became untappable. The whole
                    // 48 dp target now stays inside the player bounds in
                    // every mode.
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    // Chunk 9: the chapter playing now; tap → chapter list.
                    val chapterIndex = dev.owndemand.app.playback.chapterIndexAt(chapters, positionMs)
                    if (chapterIndex >= 0) {
                        Text(
                            "  ·  ${chapters[chapterIndex].title}" + if (onOpenChapters != null) "  ›" else "",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .onGloballyPositioned { chapterTitleBounds = it.boundsInWindow() },
                        )
                    } else {
                        chapterTitleBounds = null
                    }
                }
                IconControl(R.drawable.ic_expand, "Fullscreen", onToggleFullscreen)
            }
        }
    }
}

/** Round translucent transport button (center row). */
@Composable
private fun CircleControl(
    @DrawableRes icon: Int,
    description: String,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            // Greyed: nothing to go to (Chunk 8).
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun IconControl(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Playback-rate options (reference-app list). */
val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun speedLabel(factor: Float): String =
    if (factor == factor.toLong().toFloat()) "${factor.toLong()}x" else "${factor}x"

/** Top-row speed button + rate menu (M6). */
@Composable
private fun SpeedControl(manager: PlaybackManager, onMenuOpenChange: (Boolean) -> Unit) {
    val speed by manager.speed.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { onMenuOpenChange(menuOpen) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
            Text(
                speedLabel(speed),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            speedLabel(option),
                            color = if (option == speed) Color(0xFFA78BFA) else Color.White,
                        )
                    },
                    onClick = {
                        manager.setSpeed(option)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * Quality for this video (Chunk 3 follow-up): "Auto" follows the Settings
 * cap for the current network; a choice applies to this video only.
 */
@Composable
private fun QualityControl(manager: PlaybackManager, onMenuOpenChange: (Boolean) -> Unit) {
    val override by manager.qualityOverride.collectAsStateWithLifecycle()
    val settingsCap by manager.settingsQuality.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { onMenuOpenChange(menuOpen) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.height(44.dp).widthIn(min = 44.dp)) {
            Text(
                playerQualityLabel(override),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        "Auto (${settingsCap.label})",
                        color = if (override == null) Color(0xFFA78BFA) else Color.White,
                    )
                },
                onClick = {
                    manager.setQualityOverride(null)
                    menuOpen = false
                },
            )
            dev.owndemand.app.domain.VideoQuality.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(option.label, color = if (option == override) Color(0xFFA78BFA) else Color.White)
                    },
                    onClick = {
                        manager.setQualityOverride(option)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** Player button text: "Auto" when following Settings, else the chosen cap. */
fun playerQualityLabel(override: dev.owndemand.app.domain.VideoQuality?): String =
    override?.shortLabel ?: "Auto"

/** Thin track, red played accent, buffered grey, wide touch band, drag thumb. */
private val ScrubBandHeight = 56.dp

@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    previewUrlFor: ((Long) -> String?)? = null,
    previewFrameFor: ((Long) -> ByteArray?)? = null,
    chapters: List<dev.owndemand.app.playback.VideoChapter> = emptyList(),
    skipSegments: List<dev.owndemand.app.playback.SkipSegment> = emptyList(),
    /** A plain tap on the band, in window coordinates (opens chapters). */
    onTapInWindow: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var bandCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val latestOnTap by rememberUpdatedState(onTapInWindow)
    // The drag lambdas live across recompositions (pointerInput key is
    // durationMs), so read the live position through rememberUpdatedState:
    // releasing must seek to the last scrubbed position, not the one
    // captured when the controls were shown.
    val latestPositionMs by rememberUpdatedState(positionMs)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(ScrubBandHeight)
            .onGloballyPositioned { bandCoordinates = it }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val coords = bandCoordinates ?: return@detectTapGestures
                    latestOnTap?.invoke(coords.localToWindow(offset))
                }
            }
            .pointerInput(durationMs) {
                if (durationMs <= 0L) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onScrubStart()
                        onScrub(((offset.x / size.width).toDouble() * durationMs).toLong())
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onScrub(
                            ((change.position.x.coerceIn(0f, size.width.toFloat()) / size.width)
                                .toDouble() * durationMs).toLong(),
                        )
                    },
                    onDragEnd = { onScrubEnd(latestPositionMs) },
                    onDragCancel = { onScrubEnd(latestPositionMs) },
                )
            },
    ) {
        val trackWidth = maxWidth
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        val bufferedFraction = if (durationMs > 0) bufferedMs.toFloat() / durationMs else 0f
        // Track flush with the bottom edge of the touch band, which is the
        // bottom edge of the video (reference: the red bar sits at the
        // very edge, not inside the control row).
        // Chunk 9: one segment per chapter, split by small gaps (a single
        // segment when the video has none).
        val gaps = remember(chapters, durationMs) {
            dev.owndemand.app.playback.chapterGapFractions(chapters, durationMs)
        }
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .align(Alignment.BottomCenter),
        ) {
            val w = size.width
            val gapPx = 2.dp.toPx()
            val edges = listOf(0f) + gaps + 1f
            val played = fraction.coerceIn(0f, 1f) * w
            val buffered = bufferedFraction.coerceIn(0f, 1f) * w
            for (i in 0 until edges.size - 1) {
                val left = edges[i] * w + if (i > 0) gapPx / 2 else 0f
                val right = edges[i + 1] * w - if (i < edges.size - 2) gapPx / 2 else 0f
                if (right <= left) continue
                fun bar(end: Float, color: Color) {
                    val e = end.coerceIn(left, right)
                    if (e > left) {
                        drawRect(color, androidx.compose.ui.geometry.Offset(left, 0f),
                            androidx.compose.ui.geometry.Size(e - left, size.height))
                    }
                }
                bar(right, Color.White.copy(alpha = 0.35f))
                bar(buffered, Color.White.copy(alpha = 0.55f))
                bar(played, Color(0xFFA78BFA))
            }
            // Skip segments (Chunk 10): each marked stretch in its category
            // colour, over the chapter segments.
            if (durationMs > 0) {
                for (seg in skipSegments) {
                    val left = (seg.startMs.toFloat() / durationMs).coerceIn(0f, 1f) * w
                    val right = (seg.endMs.toFloat() / durationMs).coerceIn(0f, 1f) * w
                    if (right > left) {
                        drawRect(
                            skipSegmentColor(seg.category),
                            androidx.compose.ui.geometry.Offset(left, 0f),
                            androidx.compose.ui.geometry.Size(right - left, size.height),
                        )
                    }
                }
            }
        }
        val thumbSize = if (scrubbing) 14.dp else 8.dp
        Box(
            Modifier
                .size(thumbSize)
                .align(Alignment.BottomStart)
                .offset(
                    x = trackWidth * fraction.coerceIn(0f, 1f) - thumbSize / 2,
                    y = thumbSize / 2 - 1.5.dp,
                )
                .clip(CircleShape)
                .background(Color(0xFFA78BFA)),
        )
        if (scrubbing) {
            // BIF scrub preview: BIF frame above the thumb with
            // the timestamp under it; text-only when the part has no
            // preview frames. Once the BIF strip is in memory (prefetched
            // at video start) frames come from RAM — no network wait.
            val previewUrl = previewUrlFor?.invoke(positionMs)
            val frameMs = positionMs / 1000 * 1000
            val frame = remember(frameMs) { previewFrameFor?.invoke(frameMs) }
            val frameBitmap = remember(frame) {
                frame?.let {
                    android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                        ?.asImageBitmap()
                }
            }
            val hasVisual = frameBitmap != null || previewUrl != null
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Measured at its full height (the band is only 56 dp)
                    // and growing upward from just above the time row, so
                    // the chapter label, frame and time never overlap it.
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                    .offset(
                        x = if (hasVisual) {
                            (trackWidth * fraction - 70.dp)
                                .coerceIn(4.dp, (trackWidth - 144.dp).coerceAtLeast(4.dp))
                        } else {
                            (trackWidth * fraction - 24.dp)
                                .coerceIn(0.dp, (trackWidth - 48.dp).coerceAtLeast(0.dp))
                        },
                        y = (-50).dp,
                    ),
            ) {
                // Chunk 9: the chapter at the scrubbed position.
                val scrubChapter = dev.owndemand.app.playback.chapterIndexAt(chapters, positionMs)
                if (scrubChapter >= 0) {
                    Text(
                        chapters[scrubChapter].title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 140.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (frameBitmap != null) {
                    Image(
                        bitmap = frameBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(140.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.DarkGray),
                    )
                    Spacer(Modifier.height(2.dp))
                } else if (previewUrl != null) {
                    AsyncImage(
                        model = previewUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(140.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.DarkGray),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(formatDuration(positionMs), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SeekRipple(forward: Boolean, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (forward) {
            Icon(
                painterResource(R.drawable.ic_seek_fwd), null,
                tint = Color.White, modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        } else {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Icon(
                painterResource(R.drawable.ic_seek_back), null,
                tint = Color.White, modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun SpeedChip() {
    Row(
        Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_speed), null,
            tint = Color.White, modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("2x speed", color = Color.White, fontSize = 13.sp)
    }
}

/**
 * CC button (Chunk 3). One subtitle track and one audio track: a plain
 * on/off toggle, as before. More: a menu with Off + each subtitle
 * language, and an Audio section when the video has several audio tracks.
 */
@Composable
private fun CaptionsControl(
    manager: PlaybackManager,
    hasSubtitles: Boolean,
    captionsOn: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val tracks by manager.tracks.collectAsStateWithLifecycle()
    val textOptions = remember(tracks) { trackOptions(tracks, androidx.media3.common.C.TRACK_TYPE_TEXT) }
    val audioOptions = remember(tracks) { trackOptions(tracks, androidx.media3.common.C.TRACK_TYPE_AUDIO) }
    if (!hasSubtitles && textOptions.isEmpty() && audioOptions.size <= 1) return
    val simple = textOptions.size <= 1 && audioOptions.size <= 1
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onMenuOpenChange(open) }
    Box {
        IconButton(
            onClick = { if (simple) manager.toggleCaptions() else open = true },
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_cc),
                if (simple) "Captions" else "Captions and audio",
                tint = if (captionsOn) Color(0xFFA78BFA) else Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader("Subtitles")
            androidx.compose.material3.DropdownMenuItem(
                text = { MenuChoice("Off", selected = !captionsOn) },
                onClick = { manager.selectSubtitle(null); open = false },
            )
            textOptions.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { MenuChoice(option.label, selected = captionsOn && option.selected) },
                    onClick = { manager.selectSubtitle(option); open = false },
                )
            }
            if (audioOptions.size > 1) {
                androidx.compose.material3.HorizontalDivider()
                MenuHeader("Audio")
                audioOptions.forEach { option ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { MenuChoice(option.label, selected = option.selected) },
                        onClick = { manager.selectAudio(option); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun MenuChoice(label: String, selected: Boolean) {
    Text(if (selected) "✓  $label" else "    $label")
}

/** Scrubber colours per segment category (service-palette-inspired). */
private fun skipSegmentColor(category: dev.owndemand.app.playback.SegmentCategory): Color =
    when (category) {
        dev.owndemand.app.playback.SegmentCategory.Sponsor -> Color(0xFF00D400)
        dev.owndemand.app.playback.SegmentCategory.SelfPromo -> Color(0xFF008FD6)
        dev.owndemand.app.playback.SegmentCategory.Interaction -> Color(0xFFB0BEC5)
        dev.owndemand.app.playback.SegmentCategory.Intro -> Color(0xFF00BCD4)
        dev.owndemand.app.playback.SegmentCategory.Outro -> Color(0xFFFF9800)
        dev.owndemand.app.playback.SegmentCategory.Preview -> Color(0xFFE91E63)
        dev.owndemand.app.playback.SegmentCategory.Filler -> Color(0xFF8D6E63)
        dev.owndemand.app.playback.SegmentCategory.MusicOffTopic -> Color(0xFF009688)
    }
