package dev.owndemand.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.owndemand.app.AppContainer
import dev.owndemand.app.R
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.playback.ChannelOrder
import dev.owndemand.app.playback.PlayQueue
import dev.owndemand.app.playback.orderChannel
import dev.owndemand.app.ui.browse.HomeSort
import dev.owndemand.app.ui.browse.isNewVideo
import dev.owndemand.app.ui.channels.ChannelDetailViewModel
import dev.owndemand.app.ui.containerViewModel
import kotlinx.coroutines.delay

/** How the channel list is ordered. */
private enum class ChannelListOrder(val label: String) { AZ("A–Z"), Recent("Recently active") }

/**
 * TV Channels (Chunk 6): the channel list and, beside it, the focused
 * channel's page, which follows focus without OK. [request] (from "Go to
 * channel") selects and focuses that channel once.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvChannelsScreen(
    container: AppContainer,
    actions: TvActions,
    request: CollectionDto?,
    onRequestHandled: () -> Unit,
) {
    LaunchedEffect(Unit) { container.libraryIndex.ensureLoaded(container) }
    val collections by container.libraryIndex.collections.collectAsStateWithLifecycle()
    val items by container.libraryIndex.items.collectAsStateWithLifecycle()
    var order by rememberSaveable { mutableStateOf(ChannelListOrder.AZ) }
    val now = System.currentTimeMillis() / 1000
    val newByChannel = remember(items) { newCounts(items.orEmpty(), now) }
    val latestByChannel = remember(items) {
        val latest = HashMap<String, Long>()
        items.orEmpty().forEach { v ->
            v.Collection.forEach { t -> latest[t.tag] = maxOf(latest[t.tag] ?: 0L, v.addedAt) }
        }
        latest
    }
    val channels = remember(collections, order, latestByChannel) {
        val all = collections.orEmpty()
        when (order) {
            ChannelListOrder.AZ -> all.sortedBy { it.title.lowercase() }
            ChannelListOrder.Recent -> all.sortedByDescending { latestByChannel[it.title] ?: 0L }
        }
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = channels.firstOrNull { it.ratingKey == selectedKey } ?: channels.firstOrNull()
    val listState = rememberLazyListState()
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }

    // "Go to channel": select it, bring it into view and focus it.
    LaunchedEffect(request, channels) {
        val target = request ?: return@LaunchedEffect
        val index = channels.indexOfFirst { it.ratingKey == target.ratingKey || it.title == target.title }
        if (index < 0) return@LaunchedEffect
        selectedKey = channels[index].ratingKey
        listState.scrollToItem(index)
        delay(80)
        runCatching { requesters[channels[index].ratingKey]?.requestFocus() }
        onRequestHandled()
    }

    if (collections == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    if (channels.isEmpty()) {
        TvNotice("No channels in this library.")
        return
    }

    Row(Modifier.fillMaxSize()) {
        // Channel list.
        Column(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .padding(start = 24.dp, top = TvSafeVertical),
        ) {
            Text("Channels", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${channels.size} channels · ${order.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            PivotScroll(leading = 120.dp) {
                LazyColumn(
                    state = listState,
                    // Entering the list lands on the channel whose page is open.
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties {
                            @Suppress("DEPRECATION")
                            enter = { selected?.ratingKey?.let { requesters[it] } ?: FocusRequester.Default }
                        },
                    contentPadding = PaddingValues(bottom = 80.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "order") {
                        TvListRow(
                            title = "Order: ${order.label}",
                            onClick = {
                                order = if (order == ChannelListOrder.AZ) ChannelListOrder.Recent else ChannelListOrder.AZ
                            },
                            leading = { Icon(painterResource(R.drawable.ic_sort), null) },
                        )
                    }
                    itemsIndexed(channels, key = { _, c -> c.ratingKey }) { _, channel ->
                        val requester = requesters.getOrPut(channel.ratingKey) { FocusRequester() }
                        var focused by remember { mutableStateOf(false) }
                        // The page follows focus after a short pause, so
                        // scrolling past channels does not load each one.
                        LaunchedEffect(focused) {
                            if (focused) {
                                delay(250)
                                selectedKey = channel.ratingKey
                            }
                        }
                        val newCount = newByChannel[channel.title] ?: 0
                        TvListRow(
                            title = channel.title,
                            detail = if (newCount > 0) "$newCount new" else videoCount(channel.childCount),
                            selected = channel.ratingKey == selected?.ratingKey,
                            onClick = { selectedKey = channel.ratingKey },
                            leading = { ChannelAvatar(actions.artwork(channel.thumb), 40.dp) },
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged { focused = it.isFocused },
                        )
                    }
                }
            }
        }
        // The selected channel's page.
        Box(Modifier.weight(1f).fillMaxHeight()) {
            selected?.let { ChannelPage(container, actions, it) }
        }
    }
}

/** "1 video" / "12 videos". */
fun videoCount(n: Int): String = if (n == 1) "1 video" else "$n videos"

/** Sort choices for a channel's videos (cycled by the Sort button). */
private val ChannelSorts = listOf(HomeSort.Newest, HomeSort.Oldest, HomeSort.TitleAsc, HomeSort.DurationDesc)

@Composable
private fun ChannelPage(container: AppContainer, actions: TvActions, channel: CollectionDto) {
    val vm: ChannelDetailViewModel =
        containerViewModel(key = "tv-channel-${channel.ratingKey}") { ChannelDetailViewModel(it, channel.ratingKey) }
    val state by vm.state.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis() / 1000
    val unwatched = videos.count { it.viewCount == 0 }
    val newCount = videos.count { isNewVideo(it, now) }

    fun playChannel(order: ChannelOrder) {
        val ordered = orderChannel(state.items, order)
        val first = ordered.firstOrNull() ?: return
        container.playbackManager().startQueue(PlayQueue(channel.title, order, ordered))
        actions.play(first.ratingKey)
    }

    PivotScroll(leading = 24.dp) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = 16.dp, end = TvSafeHorizontal, top = TvSafeVertical, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    ChannelBanner(actions, channel)
                    Spacer(Modifier.height(14.dp))
                    Text(channel.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildList {
                            add(videoCount(videos.size))
                            if (unwatched > 0) add("$unwatched unwatched")
                            if (newCount > 0) add("$newCount new")
                        }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = { playChannel(ChannelOrder.NewestFirst) }, enabled = videos.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_play), null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play newest")
                        }
                        OutlinedButton(onClick = { playChannel(ChannelOrder.OldestFirst) }, enabled = videos.isNotEmpty()) {
                            Text("Play oldest")
                        }
                        OutlinedButton(onClick = { playChannel(ChannelOrder.Shuffle) }, enabled = videos.isNotEmpty()) {
                            Text("Shuffle")
                        }
                        OutlinedButton(onClick = {
                            val i = ChannelSorts.indexOf(sort)
                            vm.setSort(ChannelSorts[(i + 1).mod(ChannelSorts.size)])
                        }) {
                            Icon(painterResource(R.drawable.ic_sort), null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (sort == HomeSort.Mixed) HomeSort.Newest.label else sort.label)
                        }
                    }
                }
            }
            when {
                state.loading && videos.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                state.error != null && videos.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = vm::loadMore) { Text("Retry") }
                    }
                }
                else -> items(videos, key = { it.ratingKey }) { video ->
                    TvVideoCard(
                        item = video,
                        artworkUrl = actions.artwork(video.thumb),
                        onClick = { actions.play(video.ratingKey) },
                        onOptions = { actions.options(video) },
                        width = null,
                        showChannel = false,
                    )
                }
            }
        }
    }
}

/** The channel banner (its uploaded background art), else its avatar on a tint. */
@Composable
private fun ChannelBanner(actions: TvActions, channel: CollectionDto) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (channel.art.isNotBlank()) {
            AsyncImage(
                model = actions.artwork(channel.art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to androidx.compose.ui.graphics.Color.Transparent, 1f to bg.copy(alpha = 0.6f))))
        } else {
            Row(Modifier.matchParentSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(actions.artwork(channel.thumb), 90.dp)
            }
        }
    }
}
