package dev.owndemand.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import dev.owndemand.app.ui.common.videoClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.owndemand.app.AppContainer
import dev.owndemand.app.R
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.containerViewModel
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.browseColumns
import dev.owndemand.app.ui.common.BrandWordmark
import dev.owndemand.app.ui.common.displayTitle
import dev.owndemand.app.ui.rowCardWidth
import dev.owndemand.app.ui.common.formatUploadDate
import dev.owndemand.app.ui.common.formatDuration

/**
 * Home feed (M5): Continue-watching row + a varied feed over the
 * in-memory library index, with sort and hide-watched controls on top.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onPlay: (String) -> Unit,
    onSearch: () -> Unit,
    /** "See all" on the Continue-watching row → the Library tab. */
    onSeeAllContinue: () -> Unit = {},
) {
    val vm: HomeViewModel = containerViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val artworkFor: (ItemDto) -> String = { container.repository().artworkUrl(it.thumb) }

    // Home always loads the smart mix on the next visit (user direction
    // 2026-10-16): an explicit sort applies only to this visit.
    DisposableEffect(Unit) {
        onDispose { vm.resetSort() }
    }

    Column(Modifier.fillMaxWidth()) {
        HomeControls(
            onSort = vm::setSort,
            onSearch = onSearch,
        )

        // Multi-column browse grid (T2): 1 column on phones (exactly
        // today's list), 2 on Tab S6 portrait, 3 landscape, 4 beyond
        // 1200 dp. The gutter (and with it every non-card item's page
        // margin) is 0 in the 1-column case, so phone layout is
        // pixel-identical to the survey baseline.
        val columns = LocalLayoutMode.current.browseColumns()
        val gutter = if (columns > 1) 12.dp else 0.dp
        // Pull down at the top to reload the library (new videos, watched
        // states); the current feed stays up until the new one arrives.
        RefreshableBox(refreshing = state.refreshing, onRefresh = vm::refresh) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            if (state.continueWatching.isNotEmpty()) {
                item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueWatchingRow(
                        items = state.continueWatching,
                        artworkFor = artworkFor,
                        onPlay = onPlay,
                        onSeeAll = onSeeAllContinue,
                    )
                }
            }
            items(state.feed, key = { "feed-" + it.ratingKey }) { item ->
                FeedCard(
                    item = item,
                    artworkUrl = artworkFor(item),
                    onClick = { onPlay(item.ratingKey) },
                )
            }
            // The loading/error/empty/spacer rows are deliberately
            // unkeyed. A lazy grid keeps its first visible item's key in
            // place, so a keyed placeholder that is alone on the first
            // frame would pin the grid to the bottom once the feed is
            // inserted above it; unkeyed, the grid stays at index 0.
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
                        Button(onClick = vm::retry) { Text("Retry") }
                    }
                }
            }
            if (state.feed.isEmpty() && !state.loading && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Nothing left to watch — nice work.",
                        modifier = Modifier.padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(80.dp))
            }
        }
        }
    }
}

/**
 * Material 3 pull-to-refresh around a scrollable list: pull down at the
 * top to call [onRefresh]; the indicator spins while [refreshing].
 * Shared by Home and the channel pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) { content() }
}

/** Wordmark + search / sort controls (M5). */
@Composable
private fun HomeControls(
    onSort: (HomeSort) -> Unit,
    onSearch: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandWordmark(
            modifier = Modifier
                .height(30.dp)
                .padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        // "Smart mix" is the default, not a menu entry (user direction
        // 2026-10-16); the menu offers the explicit sorts only.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_sort), "Sort")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                HomeSort.entries.filterNot { it == HomeSort.Mixed }.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSort(option)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = onSearch) {
            Icon(
                painterResource(R.drawable.ic_tab_search),
                "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Horizontal resume row (user request 2026-10-16). */
@Composable
private fun ContinueWatchingRow(
    items: List<ItemDto>,
    artworkFor: (ItemDto) -> String,
    onPlay: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(
        Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Continue watching",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        // 200 dp on phones (unchanged), 260 dp on tablet (T2).
        val cardWidth = LocalLayoutMode.current.rowCardWidth()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.ratingKey }) { item ->
                Column(
                    Modifier
                        .width(cardWidth)
                        .videoClickable(item) { onPlay(item.ratingKey) },
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        AsyncImage(
                            model = artworkFor(item),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.DarkGray),
                        )
                        if (item.duration > 0) {
                            // Resume progress along the card's bottom edge.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(Color.White.copy(alpha = 0.35f)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(
                                            (item.viewOffset.toFloat() / item.duration)
                                                .coerceIn(0f, 1f),
                                        )
                                        .height(3.dp)
                                        .background(Color(0xFFA78BFA)),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        displayTitle(item.title, item.channelName),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** One feed row: 16:9 thumbnail + duration badge, title, channel · date. */
@Composable
fun FeedCard(
    item: ItemDto,
    artworkUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailWidth: androidx.compose.ui.unit.Dp? = null,
) {
    if (thumbnailWidth != null) {
        // Horizontal variant for up-next lists.
        Row(
            modifier.videoClickable(item, onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            ThumbnailBadge(item = item, artworkUrl = artworkUrl, modifier = Modifier.width(thumbnailWidth))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayTitle(item.title, item.channelName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.channelName ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDuration(item.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Column(modifier.fillMaxWidth().videoClickable(item, onClick)) {
            ThumbnailBadge(item = item, artworkUrl = artworkUrl, modifier = Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    displayTitle(item.title, item.channelName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(item.channelName, formatUploadDate(item))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 16:9 thumbnail with the duration chip bottom-right (spec §5), plus M5
 * watched affordances: a red resume bar along the bottom edge when
 * partially watched, and a "Watched" chip when finished.
 */
@Composable
private fun ThumbnailBadge(
    item: ItemDto,
    artworkUrl: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.aspectRatio(16f / 9f)) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().background(Color.DarkGray),
        )
        if (item.isPartiallyWatched && item.duration > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((item.viewOffset.toFloat() / item.duration).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Color(0xFFA78BFA)),
                )
            }
        }
        // "New": never played and added within the last 7 days.
        if (isNewVideo(item, System.currentTimeMillis() / 1000)) {
            Text(
                "New",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF4F46E5))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (item.isFullyWatched) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text("Watched", color = Color.White, fontSize = 11.sp)
            }
        } else if (item.duration > 0) {
            Text(
                formatDuration(item.duration),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
