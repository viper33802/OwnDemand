package dev.owndemand.app.ui.watch

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.browse.FeedCard
import dev.owndemand.app.ui.common.displayTitle
import dev.owndemand.app.ui.common.findActivity
import dev.owndemand.app.ui.common.formatAddedAt
import dev.owndemand.app.ui.common.formatDuration
import dev.owndemand.app.ui.containerViewModel
import dev.owndemand.app.ui.isTablet
import dev.owndemand.app.ui.player.VideoPlayer
import dev.owndemand.app.ui.useSideRail
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.ui.draw.clip

/**
 * Playback-first watch screen (AGENTS.md §5): the video starts
 * immediately inline; the description is an expandable card below the
 * player; up-next keeps the page browsable while playing. Overlays the
 * shell's tab area (bottom menu stays visible, user direction 2026-10-16);
 * fullscreen is hoisted to the shell so it can hide the menu.
 */
@Composable
fun WatchScreen(
    container: AppContainer,
    ratingKey: String,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenChannel: (String) -> Unit,
    /** Opens another video (Up next) as its own watch screen. */
    onPlay: (String) -> Unit,
    /** Autoplay: opens the next video, keeping fullscreen as it is. */
    onAutoplay: (String) -> Unit = onPlay,
) {
    val vm: WatchViewModel = containerViewModel(key = ratingKey) { WatchViewModel(it, ratingKey) }
    val item by vm.item.collectAsStateWithLifecycle()
    val upNext by vm.upNext.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    // Autoplay next (Chunk 3): when this video ends, count down 5 s on top
    // of the player, then open the first Up-next video not yet finished.
    // One global switch in Settings; Cancel stops it for this ending.
    val endedKey by vm.playback.endedKey.collectAsStateWithLifecycle()
    val autoplayOn = container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = null).value?.autoplay == true
    val nextUp = pickAutoplayNext(upNext)
    var cancelledFor by remember(ratingKey) { mutableStateOf<String?>(null) }
    var secondsLeft by remember(ratingKey) { mutableIntStateOf(AUTOPLAY_SECONDS) }
    val countdownNext = nextUp.takeIf { autoplayOn && endedKey == ratingKey && cancelledFor != endedKey }
    LaunchedEffect(countdownNext?.ratingKey, endedKey) {
        val next = countdownNext ?: return@LaunchedEffect
        secondsLeft = AUTOPLAY_SECONDS
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1_000)
            secondsLeft--
        }
        onAutoplay(next.ratingKey)
    }
    val playerOverlay: (@Composable BoxScope.() -> Unit)? = countdownNext?.let { next ->
        {
            AutoplayCountdown(
                next = next,
                secondsLeft = secondsLeft,
                onPlayNow = { onAutoplay(next.ratingKey) },
                onCancel = { cancelledFor = endedKey },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }

    // Leaving the watch screen hands playback to the miniplayer (M4): the
    // player keeps running and the timeline loop keeps reporting.
    // Re-entering the same video resumes its position; onEnter switches
    // the player when it holds another video (or none) — the VM is cached
    // per video, so its init does not run again.
    LaunchedEffect(ratingKey) { vm.onEnter() }

    val activity = LocalContext.current.findActivity()
    val view = LocalView.current
    // Tablet (T4): the phone's orientation rules — portrait lock and
    // rotate-to-fullscreen — are phone-only. A tablet rotates freely in
    // inline watch; the layout itself switches (two-pane landscape ↔
    // stacked portrait) instead of the mode changing.
    val mode = LocalLayoutMode.current
    val tablet = mode.isTablet

    // Orientation drives the mode (user rule 2026-10-16, phone behavior):
    // a physical turn to landscape → fullscreen; a turn back to portrait →
    // inline. The only exception is the collapse button: collapsing while
    // the phone is still held landscape disarms the landscape trigger until
    // the phone is upright again, so it doesn't instantly re-expand.
    var rotateArmed by rememberSaveable { mutableStateOf(true) }
    DisposableEffect(fullscreen, tablet) {
        val window = activity?.window
        val controller = window?.let {
            WindowInsetsControllerCompat(it, view)
        }
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        if (fullscreen) {
            activity?.requestedOrientation =
                if (tablet) ActivityInfo.SCREEN_ORIENTATION_USER // tablets keep the held orientation
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            activity?.requestedOrientation =
                if (tablet) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Video-first: the status bar is hidden on the watch screen so
            // the player's top row sits at the true top edge (the AVD's
            // status-bar inset includes the display cutout, which pushed the
            // controls far below the shading). The nav bar stays visible.
            controller?.apply {
                hide(WindowInsetsCompat.Type.statusBars())
                show(WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
        val listener = object : android.view.OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val landscape = degrees in 75..105 || degrees in 255..285
                val upright = degrees < 20 || degrees > 340
                when {
                    upright -> {
                        rotateArmed = true
                        // Phones return to inline when stood upright. On a
                        // tablet this sensor fires for a device lying flat
                        // (verified on the emulator — it collapsed
                        // fullscreen 100 ms after opening), so tablets
                        // collapse only via the on-screen button.
                        if (fullscreen && !tablet) onFullscreenChange(false)
                    }
                    // Tablets never auto-expand: the two-pane inline layout
                    // IS the tablet landscape experience (T4).
                    landscape && rotateArmed && !fullscreen && !tablet -> {
                        rotateArmed = false
                        onFullscreenChange(true)
                    }
                }
            }
        }
        listener.enable()
        onDispose {
            listener.disable()
            // Leaving the watch screen restores the system bars + rotation.
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler {
        if (fullscreen) {
            onFullscreenChange(false)
            rotateArmed = false
        } else {
            onBack()
        }
    }

    if (item == null && error == null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) { CircularProgressIndicator() }
        return
    }

    error?.let { message ->
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
        ) {
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val current = item ?: return
    val artworkFor: (ItemDto) -> String = { container.repository().artworkUrl(it.thumb) }

    if (fullscreen) {
        VideoPlayer(
            manager = vm.playback,
            title = displayTitle(current.title, current.channelName),
            fullscreen = true,
            onToggleFullscreen = {
                onFullscreenChange(false)
                rotateArmed = false
            },
            onDock = onBack,
            overlay = playerOverlay,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Pinned player + description; only the up-next list scrolls (the video
    // never scrolls away — AGENTS.md §5). Surface (not just a background)
    // so default Text color follows the theme: without it LocalContentColor
    // stays black and titles vanish into the dark background (M4c fix).
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (mode.useSideRail) {
            // T4 landscape two-pane (fixes survey A1): player + card left,
            // Up-next scrolls right — everything on screen at 1138×711 dp,
            // no scrolling needed to reach the card or the list.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.6f)) {
                    VideoPlayer(
                        manager = vm.playback,
                        title = displayTitle(current.title, current.channelName),
                        fullscreen = false,
                        onToggleFullscreen = { onFullscreenChange(true) },
                        onDock = onBack,
                        overlay = playerOverlay,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                    DescriptionCard(current, onOpenChannel)
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Column(
                    Modifier
                        .weight(0.4f)
                        .fillMaxHeight(),
                ) {
                    Text(
                        "Up next",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(upNext, key = { it.ratingKey }) { next ->
                            FeedCard(
                                item = next,
                                artworkUrl = artworkFor(next),
                                onClick = { onPlay(next.ratingKey) },
                                thumbnailWidth = 150.dp,
                            )
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                VideoPlayer(
                    manager = vm.playback,
                    title = displayTitle(current.title, current.channelName),
                    fullscreen = false,
                    onToggleFullscreen = { onFullscreenChange(true) },
                    onDock = onBack,
                    overlay = playerOverlay,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                DescriptionCard(current, onOpenChannel)
                HorizontalDivider()
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(upNext, key = { it.ratingKey }) { next ->
                        FeedCard(
                            item = next,
                            artworkUrl = artworkFor(next),
                            onClick = { onPlay(next.ratingKey) },
                            thumbnailWidth = 150.dp,
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** Expandable title/channel/summary card — readable while playing (spec §8). */
@Composable
private fun DescriptionCard(item: ItemDto, onOpenChannel: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            displayTitle(item.title, item.channelName),
            style = MaterialTheme.typography.titleMedium,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        // Channel name links to the channel page (user direction 2026-10-16);
        // the date/duration trail stays plain text.
        Row(Modifier.fillMaxWidth()) {
            val channel = item.channelName
            if (!channel.isNullOrBlank()) {
                Text(
                    channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onOpenChannel(channel) },
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                listOfNotNull(
                    formatAddedAt(item.addedAt).takeIf { it.isNotBlank() },
                    formatDuration(item.duration),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (expanded) item.summary else item.summary.lineSequence().first(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Expanded summary scrolls within a capped band so the pinned
                // header never pushes the up-next list off-screen.
                modifier = if (expanded) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier.fillMaxWidth()
                },
            )
            // The only expand control (user direction 2026-10-16): tapping
            // the card body must stay free for scrub-adjacent gestures.
            Text(
                text = if (expanded) "Show less" else "…more",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(top = 8.dp, bottom = 4.dp, end = 24.dp),
            )
        }
    }
}

/** Autoplay's next video: the first Up-next one not already finished. */
fun pickAutoplayNext(upNext: List<ItemDto>): ItemDto? = upNext.firstOrNull { !it.isFullyWatched }

/** Seconds the autoplay countdown waits before opening the next video. */
const val AUTOPLAY_SECONDS = 5

/** "Up next in N" card over the player: Play now / Cancel. */
@Composable
private fun AutoplayCountdown(
    next: ItemDto,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("Up next in $secondsLeft", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        Text(
            displayTitle(next.title, next.channelName),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onPlayNow) { Text("Play now") }
        }
    }
}
