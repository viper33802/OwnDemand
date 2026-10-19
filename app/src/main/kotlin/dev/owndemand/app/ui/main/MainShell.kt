package dev.owndemand.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owndemand.app.AppContainer
import dev.owndemand.app.LocalAppContainer
import dev.owndemand.app.R
import dev.owndemand.app.data.settings.AppSettings
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.browse.HomeScreen
import dev.owndemand.app.ui.channels.ChannelsScreen
import dev.owndemand.app.ui.player.FloatingMiniPlayer
import dev.owndemand.app.ui.search.SearchScreen
import dev.owndemand.app.ui.settings.SettingsSheet
import dev.owndemand.app.ui.useSideRail
import dev.owndemand.app.ui.watch.WatchScreen

private data class Tab(val label: String, @DrawableRes val iconRes: Int)

private val tabs = listOf(
    Tab("Home", R.drawable.ic_tab_home),
    Tab("Channels", R.drawable.ic_tab_channel),
    Tab("Settings", R.drawable.ic_settings),
)

/**
 * Main shell. Phones and portrait tablets navigate via a bottom bar
 * (centred and width-capped from 560 dp up, survey A4); landscape/desktop
 * widths (≥840 dp) swap it for a left [NavigationRail] (T3, plan §3.1),
 * with Settings pinned at the rail foot and search as its own icon.
 * Home feed, Channels (Collections) and Settings are the tabs; Playlists
 * were removed 2026-10-16: verified redundant (Youtarr `YT:` playlists
 * fully covered by channel collections; the only other playlist pointed
 * at the TV Shows library). The watch screen overlays the tab area but
 * keeps the menu visible while playing portrait (user direction
 * 2026-10-16); only fullscreen playback takes the whole window. The
 * floating miniplayer (live video, drag to any corner) shows on every tab
 * while a video plays outside the watch screen — inside the content area,
 * so the rail is never a docking obstacle.
 */
@Composable
fun MainShell(
    mainVm: MainViewModel,
) {
    val container = LocalAppContainer.current
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    val selectedTab by mainVm.selectedTab.collectAsStateWithLifecycle()
    val searchOpen by mainVm.searchOpen.collectAsStateWithLifecycle()
    val watchKey by mainVm.watchKey.collectAsStateWithLifecycle()
    val watchFullscreen by mainVm.watchFullscreen.collectAsStateWithLifecycle()

    // Back closes the search overlay instead of leaving the shell.
    BackHandler(enabled = searchOpen && watchKey == null) { mainVm.closeSearch() }

    val playback = container.playbackManager()
    val miniItem by playback.currentItem.collectAsStateWithLifecycle()

    // Fullscreen playback owns the whole window: menu and system bars go.
    val fullscreenNow = watchKey != null && watchFullscreen
    val useRail = LocalLayoutMode.current.useSideRail && !fullscreenNow

    Scaffold(
        bottomBar = {
            if (!fullscreenNow && !useRail) {
                // Centred and width-capped (survey A4): on phones (≤560 dp)
                // this changes nothing; on tablet-portrait widths the three
                // items no longer scatter to the screen edges.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    NavigationBar(modifier = Modifier.widthIn(max = 560.dp)) {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selectedTab == index && watchKey == null,
                                onClick = { mainVm.selectTab(index) },
                                icon = {
                                    Icon(
                                        painter = painterResource(tab.iconRes),
                                        contentDescription = tab.label,
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val topPad = padding.calculateTopPadding()
        val bottomPad = padding.calculateBottomPadding()

        // The watch screen manages its own insets (status bar hidden
        // inline; the whole window in fullscreen), so it must not inherit
        // the Scaffold's top inset — only the bottom one, to sit above the
        // menu / gesture bar.
        val contentModifier = when {
            fullscreenNow -> Modifier.fillMaxSize()
            watchKey != null -> Modifier
                .fillMaxSize()
                .padding(bottom = bottomPad)
            else -> Modifier.fillMaxSize().padding(padding)
        }

        val content: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                ShellContent(
                    container = container,
                    mainVm = mainVm,
                    settings = settings,
                    selectedTab = selectedTab,
                    searchOpen = searchOpen,
                    watchKey = watchKey,
                    watchFullscreen = watchFullscreen,
                    showMini = miniItem != null,
                    playback = playback,
                )
            }
        }

        if (useRail) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = topPad, bottom = bottomPad),
                    // Scaffold already applied the window insets around us.
                    windowInsets = WindowInsets(0.dp),
                ) {
                    Spacer(Modifier.height(6.dp))
                    IconButton(onClick = mainVm::openSearch) {
                        Icon(painterResource(R.drawable.ic_tab_search), "Search")
                    }
                    // Home + Channels lead; Settings is pinned to the
                    // rail foot (Material side-rail convention).
                    tabs.take(2).forEachIndexed { index, tab ->
                        NavigationRailItem(
                            selected = selectedTab == index && watchKey == null,
                            onClick = { mainVm.selectTab(index) },
                            icon = {
                                Icon(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = selectedTab == 2 && watchKey == null,
                        onClick = { mainVm.selectTab(2) },
                        icon = {
                            Icon(
                                painterResource(tabs[2].iconRes),
                                tabs[2].label,
                            )
                        },
                        label = { Text(tabs[2].label) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                content(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            top = if (watchKey != null) 0.dp else topPad,
                            bottom = bottomPad,
                        ),
                )
            }
        } else {
            content(contentModifier)
        }
    }
}

/** Tab/watch/search content shared by the bottom-bar and rail layouts. */
@Composable
private fun ShellContent(
    container: AppContainer,
    mainVm: MainViewModel,
    settings: AppSettings,
    selectedTab: Int,
    searchOpen: Boolean,
    watchKey: String?,
    watchFullscreen: Boolean,
    showMini: Boolean,
    playback: dev.owndemand.app.playback.PlaybackManager,
) {
    val watching = watchKey
    if (watching != null) {
        WatchScreen(
            container = container,
            ratingKey = watching,
            fullscreen = watchFullscreen,
            onFullscreenChange = mainVm::setWatchFullscreen,
            onBack = mainVm::closeWatch,
            onOpenChannel = mainVm::openChannelByName,
        )
    } else {
        if (searchOpen) {
            SearchScreen(
                container = container,
                onPlay = mainVm::play,
                onOpenChannel = mainVm::openChannelFromSearch,
                onClose = mainVm::closeSearch,
            )
        } else when (selectedTab) {
            0 -> HomeScreen(
                container = container,
                onPlay = mainVm::play,
                onSearch = mainVm::openSearch,
            )
            1 -> ChannelsScreen(
                container = container,
                openChannel = mainVm.openChannel.collectAsStateWithLifecycle().value,
                onOpenChannel = { mainVm.openChannel.value = it },
                onPlay = mainVm::play,
            )
            else -> SettingsSheet(
                settings = settings,
                onSignOut = mainVm::signOut,
                onChangeLibrary = mainVm::changeLibrary,
                onSwitchServer = mainVm::switchServer,
                onDataSaverChange = mainVm::setDataSaver,
                onFontScaleChange = mainVm::setFontScale,
            )
        }

        // Floating playback (M4e): live video card, draggable, snaps
        // to corners; hidden while the watch screen shows the video.
        if (showMini) {
            FloatingMiniPlayer(
                manager = playback,
                onExpand = { playback.currentRatingKey?.let(mainVm::play) },
                onClose = playback::stopAndReport,
            )
        }
    }
}
