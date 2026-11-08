package dev.owndemand.app.ui.tv

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import dev.owndemand.app.AppContainer
import dev.owndemand.app.R
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.common.displayTitle
import dev.owndemand.app.ui.common.formatDuration
import dev.owndemand.app.ui.common.videoMenuOptions
import dev.owndemand.app.ui.main.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The TV rail's destinations, top to bottom. */
enum class TvDest(val label: String, @DrawableRes val icon: Int) {
    Search("Search", R.drawable.ic_tab_search),
    Home("Home", R.drawable.ic_tab_home),
    Channels("Channels", R.drawable.ic_tab_channel),
    Library("Library", R.drawable.ic_tab_library),
    Settings("Settings", R.drawable.ic_settings),
}

/** What every TV screen can ask the shell to do. */
class TvActions(
    val play: (ratingKey: String) -> Unit,
    val playFromStart: (ratingKey: String) -> Unit,
    val options: (ItemDto) -> Unit,
    val openChannel: (CollectionDto) -> Unit,
    val artwork: (path: String) -> String,
)

/**
 * TV main shell (Chunk 6): the expanding side rail and one screen beside
 * it; the full-screen player and the video options on top.
 *
 * Back: from a screen → the rail; on the rail → Home; on Home's rail →
 * leave the app (the system's own Back).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvShell(container: AppContainer, mainVm: MainViewModel) {
    var dest by rememberSaveable { mutableStateOf(TvDest.Home) }
    // The open video lives in MainViewModel (watchKey), not here: picture-
    // in-picture replaces this shell, and coming back must reopen the player.
    val watchKey by mainVm.watchKey.collectAsStateWithLifecycle()
    var startOverKey by remember { mutableStateOf<String?>(null) }
    val player = watchKey?.let { TvPlayRequest(it, fromStart = it == startOverKey) }
    fun openPlayer(key: String, fromStart: Boolean) {
        startOverKey = if (fromStart) key else null
        mainVm.play(key)
    }
    fun closePlayer() {
        startOverKey = null
        mainVm.closeWatch()
    }
    var optionsFor by remember { mutableStateOf<ItemDto?>(null) }
    var channelRequest by remember { mutableStateOf<CollectionDto?>(null) }
    var railFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val content = remember { FocusRequester() }
    val railItems = remember { TvDest.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    val focusedVideo = remember { mutableStateOf<ItemDto?>(null) }

    val actions = remember {
        TvActions(
            play = { key -> openPlayer(key, fromStart = false) },
            playFromStart = { key -> openPlayer(key, fromStart = true) },
            options = { item -> optionsFor = item },
            openChannel = { channel ->
                channelRequest = channel
                dest = TvDest.Channels
            },
            artwork = { path -> container.repository().artworkUrl(path) },
        )
    }

    // Back from a screen goes to the rail; from the rail, to Home.
    BackHandler(enabled = player == null && !railFocused) {
        runCatching { railItems.getValue(dest).requestFocus() }
    }
    BackHandler(enabled = player == null && railFocused && dest != TvDest.Home) {
        dest = TvDest.Home
        runCatching { railItems.getValue(TvDest.Home).requestFocus() }
    }

    // Focus starts in the content (Home's first row), not on the rail —
    // once the library has loaded and there is something to focus.
    LaunchedEffect(Unit) {
        repeat(40) {
            if (contentFocused || railFocused || player != null) return@LaunchedEffect
            runCatching { content.requestFocus() }
            delay(150)
        }
    }

    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // Back goes straight to the back handlers (screens, rail, player):
    // otherwise Compose first spends it moving focus out to a parent.
    // The remote's Menu key: the focused video's options, on any screen.
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back && backDispatcher != null) {
                    if (e.type == KeyEventType.KeyUp) backDispatcher.onBackPressed()
                    return@onPreviewKeyEvent true
                }
                if (e.key != Key.Menu || player != null) return@onPreviewKeyEvent false
                val video = focusedVideo.value ?: return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) optionsFor = video
                true
            },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalFocusedVideo provides focusedVideo) {
        NavigationDrawer(
            drawerContent = { value ->
                Column(
                    Modifier
                        .fillMaxHeight()
                        .padding(vertical = TvSafeVertical, horizontal = 12.dp)
                        .onFocusChanged { railFocused = it.hasFocus }
                        // Entering the rail lands on the screen in view, not
                        // on whichever item happens to be nearest.
                        .focusProperties {
                            @Suppress("DEPRECATION")
                            enter = { railItems.getValue(dest) }
                        }
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RailHeader(expanded = value == DrawerValue.Open, mainVm = mainVm)
                    Spacer(Modifier.height(18.dp))
                    TvDest.entries.forEach { d ->
                        if (d == TvDest.Settings) Spacer(Modifier.weight(1f))
                        // Focusing a destination shows it (after a short pause,
                        // so passing over the rail loads nothing); OK enters it.
                        var focusedHere by remember { mutableStateOf(false) }
                        LaunchedEffect(focusedHere) {
                            if (focusedHere && dest != d) {
                                delay(RAIL_SWITCH_DELAY_MS)
                                dest = d
                            }
                        }
                        NavigationDrawerItem(
                            selected = dest == d,
                            onClick = {
                                dest = d
                                scope.launch {
                                    delay(60)
                                    runCatching { content.requestFocus() }
                                }
                            },
                            leadingContent = { Icon(painterResource(d.icon), contentDescription = null) },
                            modifier = Modifier
                                .focusRequester(railItems.getValue(d))
                                .onFocusChanged { focusedHere = it.isFocused },
                        ) {
                            Text(d.label, maxLines = 1)
                        }
                    }
                }
            },
            drawerState = rememberDrawerState(DrawerValue.Closed),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(content)
                    .onFocusChanged { contentFocused = it.hasFocus }
                    .focusRestorer()
                    .focusGroup(),
            ) {
                when (dest) {
                    TvDest.Search -> TvSearchScreen(container, actions)
                    TvDest.Home -> TvHomeScreen(container, actions)
                    TvDest.Channels -> TvChannelsScreen(container, actions, channelRequest) { channelRequest = null }
                    TvDest.Library -> TvLibraryScreen(container, actions)
                    TvDest.Settings -> TvSettingsScreen(container, mainVm)
                }
            }
        }

        }

        player?.let { request ->
            TvPlayerScreen(
                container = container,
                request = request,
                onPlay = { key -> openPlayer(key, fromStart = false) },
                onOpenChannel = { name ->
                    container.libraryIndex.collections.value
                        ?.firstOrNull { it.title.equals(name, ignoreCase = true) }
                        ?.let { channel ->
                            // No miniplayer on TV: leaving the player stops it
                            // (the position is saved, as with Back).
                            container.playbackManager().stopAndReport()
                            closePlayer()
                            actions.openChannel(channel)
                        }
                },
                onExit = {
                    closePlayer()
                    scope.launch {
                        delay(60)
                        runCatching { content.requestFocus() }
                    }
                },
            )
        }

        optionsFor?.let { item ->
            TvVideoOptions(
                item = item,
                container = container,
                actions = actions,
                onDismiss = { optionsFor = null },
            )
        }
    }
}

/** Shows a destination this long after the rail's focus settles on it. */
private const val RAIL_SWITCH_DELAY_MS = 350L

/** Account initial (collapsed) or name and library (expanded) atop the rail. */
@Composable
private fun RailHeader(expanded: Boolean, mainVm: MainViewModel) {
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    val name = settings.accountUsername ?: "OwnDemand"
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(50),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(
                    name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                settings.library?.title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A video's options (long-press OK or Menu on a card): play / resume /
 * from the start, watched state, Continue watching, its channel.
 */
@Composable
fun TvVideoOptions(
    item: ItemDto,
    container: AppContainer,
    actions: TvActions,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val hidden = settings?.hiddenContinue?.get(item.ratingKey) == item.viewOffset
    val opts = videoMenuOptions(item, hiddenFromContinue = hidden)
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        TvTheme {
            Surface(
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.width(520.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusOnStart(first)
                    Text(
                        displayTitle(item.title, item.channelName),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.channelName?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    val resume = item.isPartiallyWatched
                    TvListRow(
                        title = if (resume) "Resume from ${formatDuration(item.viewOffset)}" else "Play",
                        onClick = {
                            onDismiss()
                            actions.play(item.ratingKey)
                        },
                        modifier = Modifier.focusRequester(first),
                    )
                    if (item.viewOffset > 0 || item.isFullyWatched) {
                        TvListRow(title = "Play from the beginning", onClick = {
                            onDismiss()
                            actions.playFromStart(item.ratingKey)
                        })
                    }
                    if (opts.markWatched) {
                        TvListRow(title = "Mark as watched", onClick = {
                            onDismiss()
                            scope.launch { container.markWatched(item.ratingKey) }
                        })
                    }
                    if (opts.markUnwatched) {
                        TvListRow(
                            title = if (item.isFullyWatched) "Mark as unwatched" else "Mark as unwatched (clear progress)",
                            onClick = {
                                onDismiss()
                                scope.launch { container.markUnwatched(item.ratingKey) }
                            },
                        )
                    }
                    if (opts.removeFromContinue) {
                        TvListRow(title = "Remove from Continue watching", onClick = {
                            onDismiss()
                            scope.launch {
                                container.settingsRepository.hideFromContinueWatching(item.ratingKey, item.viewOffset)
                            }
                        })
                    }
                    if (opts.goToChannel) {
                        TvListRow(title = "Go to ${item.channelName}", onClick = {
                            onDismiss()
                            container.libraryIndex.collections.value
                                ?.firstOrNull { it.title.equals(item.channelName, ignoreCase = true) }
                                ?.let(actions.openChannel)
                        })
                    }
                }
            }
        }
    }
}
