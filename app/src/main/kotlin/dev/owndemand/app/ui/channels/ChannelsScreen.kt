package dev.owndemand.app.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.owndemand.app.AppContainer
import dev.owndemand.app.LocalAppContainer
import dev.owndemand.app.R
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.browseColumns
import dev.owndemand.app.ui.browse.HomeSort
import dev.owndemand.app.ui.browse.FeedCard
import dev.owndemand.app.ui.browse.RefreshableBox
import dev.owndemand.app.ui.containerViewModel
import dev.owndemand.app.ui.detailPaneColumns
import dev.owndemand.app.ui.isTablet

/**
 * Channels tab: Plex Collections as a channel list (AGENTS.md §4.1),
 * Subscription-list-style rows — round avatar, name, video count
 * (user direction 2026-10-16: no vertical cards). Selecting a channel
 * opens its videos in place of the list.
 */
@Composable
fun ChannelsScreen(
    container: AppContainer,
    openChannel: CollectionDto?,
    onOpenChannel: (CollectionDto?) -> Unit,
    onPlay: (String) -> Unit,
) {
    // Tablet (user direction 2026-10-17): two-pane master-detail — a
    // left rail of channel names beside the selected channel's videos
    // (2 columns landscape, 1 portrait on the Tab S6). Phones keep the
    // drill-in list → full-screen channel page.
    if (LocalLayoutMode.current.isTablet) {
        TwoPaneChannels(
            container = container,
            openChannel = openChannel,
            onOpenChannel = onOpenChannel,
            onPlay = onPlay,
        )
        return
    }

    openChannel?.let { channel ->
        ChannelDetailScreen(
            channel = channel,
            onBack = { onOpenChannel(null) },
            onPlay = onPlay,
        )
        return
    }

    val vm: ChannelsViewModel = containerViewModel { ChannelsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    // T2: channels tile in a grid at tablet widths (survey A3), a single
    // column on phones with zero gutter — pixel-identical to before.
    val columns = LocalLayoutMode.current.browseColumns()
    val gutter = if (columns > 1) 12.dp else 0.dp
    val listState = rememberLazyGridState()

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 8
        }.collect { nearEnd -> if (nearEnd) vm.loadMore() }
    }

    LazyVerticalGrid(
        state = listState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp, start = gutter, end = gutter),
        horizontalArrangement = Arrangement.spacedBy(gutter),
    ) {
        items(state.items, key = { it.ratingKey }) { collection ->
            ChannelRow(
                collection = collection,
                artworkUrl = container.repository().artworkUrl(collection.thumb),
                onClick = { onOpenChannel(collection) },
            )
        }
        // Unkeyed on purpose (see HomeScreen): a keyed placeholder alone
        // on the first frame would anchor the list below the content.
        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.error?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(message, modifier = Modifier.weight(1f))
                    Button(onClick = vm::loadMore) { Text("Retry") }
                }
            }
        }
    }
}

/**
 * Tablet Channels (user direction 2026-10-17): left rail lists every
 * channel; the rest of the window shows the selected one's videos.
 * The rail width matches the reference app's tablet subscription list; the detail
 * pane derives its own column count (≈2 in Tab S6 landscape, 1 in
 * portrait — [detailPaneColumns]).
 */
@Composable
private fun TwoPaneChannels(
    container: AppContainer,
    openChannel: CollectionDto?,
    onOpenChannel: (CollectionDto?) -> Unit,
    onPlay: (String) -> Unit,
) {
    val vm: ChannelsViewModel = containerViewModel { ChannelsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 8
        }.collect { nearEnd -> if (nearEnd) vm.loadMore() }
    }

    // Never show an empty detail pane: default to the first channel.
    LaunchedEffect(state.items, openChannel) {
        if (openChannel == null && state.items.isNotEmpty()) {
            onOpenChannel(state.items.first())
        }
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp, start = 8.dp, end = 8.dp),
        ) {
            items(state.items, key = { it.ratingKey }) { collection ->
                ChannelRow(
                    collection = collection,
                    artworkUrl = container.repository().artworkUrl(collection.thumb),
                    selected = collection.ratingKey == openChannel?.ratingKey,
                    edgePadding = 12.dp,
                    onClick = { onOpenChannel(collection) },
                )
            }
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.error?.let { message ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = vm::loadMore) { Text("Retry") }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val channel = openChannel
            if (channel == null) {
                Text(
                    "Select a channel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // No back affordance here — the rail is the way back.
                // Pulling the channel also refreshes the rail beside it.
                ChannelDetailScreen(
                    channel = channel,
                    onBack = null,
                    onPlay = onPlay,
                    onRefreshChannels = vm::refresh,
                )
            }
        }
    }
}

/** One channel: round avatar, name, video count — a subscription row, not a poster. */
@Composable
private fun ChannelRow(
    collection: CollectionDto,
    artworkUrl: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    edgePadding: Dp = 16.dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = edgePadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = collection.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.DarkGray),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                collection.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${collection.childCount} video${if (collection.childCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Channel page: a PINNED header (back, identity, sort) above the video
 * list — scrolling a long channel never hides the way back (user request
 * 2026-10-16). Videos load whole-channel and sort newest-first by default.
 */
@Composable
fun ChannelDetailScreen(
    channel: CollectionDto,
    onBack: (() -> Unit)?,
    onPlay: (String) -> Unit,
    /** Tablet two-pane: also reload the channel rail on pull-to-refresh. */
    onRefreshChannels: (() -> Unit)? = null,
) {
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val container = LocalAppContainer.current
    val vm: ChannelDetailViewModel =
        containerViewModel(key = channel.ratingKey) { ChannelDetailViewModel(it, channel.ratingKey) }
    val state by vm.state.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (onBack != null) {
            // Phone drill-in gets the visible back arrow; in the tablet
            // two-pane the rail on the left is the way back.
            Text(
                "←",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
            AsyncImage(
                model = container.repository().artworkUrl(channel.thumb),
                contentDescription = channel.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(48.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.DarkGray),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The loaded list is fresher than the collection's count
                // once the page (or a pull-to-refresh) has loaded.
                val count = if (state.items.isNotEmpty()) state.items.size else channel.childCount
                Text(
                    "$count video${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_sort), "Sort")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    HomeSort.entries.filterNot { it == HomeSort.Mixed }.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                vm.setSort(option)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }

        // T2: video grid. Column count comes from THIS pane's width, so
        // the tablet rail leaves 2 columns (Tab S6 landscape) or 1
        // (portrait); the phone full-screen page still resolves to 1.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = detailPaneColumns(maxWidth)
            val gutter = if (columns > 1) 12.dp else 0.dp
            RefreshableBox(
                refreshing = state.refreshing,
                onRefresh = {
                    vm.refresh()
                    onRefreshChannels?.invoke()
                },
            ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(gutter),
            ) {
            items(videos, key = { it.ratingKey }) { video ->
                FeedCard(
                    item = video,
                    artworkUrl = container.repository().artworkUrl(video.thumb),
                    onClick = { onPlay(video.ratingKey) },
                )
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.error?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        Button(onClick = vm::loadMore) { Text("Retry") }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(80.dp))
            }
            }
            }
        }
    }
}
