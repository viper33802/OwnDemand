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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.ui.search.searchChannels
import dev.owndemand.app.ui.search.searchItems

/** The on-screen keyboard's letter keys, six per row. */
private val SearchKeys = ("abcdefghijklmnopqrstuvwxyz1234567890").map { it.toString() }

/**
 * TV Search (Chunk 6): an on-screen letter grid — no system keyboard, so
 * it works the same with every remote — and results that update as you
 * type: matching channels, then videos. Searches the in-memory library
 * (titles and channel names), like the phone search.
 */
@Composable
fun TvSearchScreen(container: AppContainer, actions: TvActions) {
    LaunchedEffect(Unit) { container.libraryIndex.ensureLoaded(container) }
    val items by container.libraryIndex.items.collectAsStateWithLifecycle()
    val collections by container.libraryIndex.collections.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val channels = remember(collections, query) { searchChannels(collections.orEmpty(), query) }
    val videos = remember(items, query) {
        if (query.isBlank()) items.orEmpty().sortedByDescending { it.addedAt }.take(24)
        else searchItems(items.orEmpty(), query)
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(330.dp)
                .fillMaxHeight()
                .padding(start = 24.dp, top = TvSafeVertical, end = 12.dp),
        ) {
            Text("Search", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    query.ifEmpty { "Titles and channels" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchKeys.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { key -> Key(key, width = 44.dp) { query += key } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key("Space", width = 94.dp) { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " }
                    Key("⌫", width = 94.dp) { query = query.dropLast(1) }
                    Key("Clear", width = 94.dp) { query = "" }
                }
            }
        }
        PivotScroll(leading = 40.dp) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
                contentPadding = PaddingValues(start = 16.dp, end = TvSafeHorizontal, top = TvSafeVertical, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (channels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { TvSectionTitle("Channels · ${channels.size}") }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(channels, key = { it.ratingKey }) { channel ->
                                ChannelChip(channel, actions)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TvSectionTitle(
                        when {
                            query.isBlank() -> "Recently added"
                            videos.isEmpty() -> "No videos match “${query.trim()}”"
                            else -> "Videos · ${videos.size}"
                        },
                    )
                }
                items(videos, key = { it.ratingKey }) { video ->
                    TvVideoCard(
                        item = video,
                        artworkUrl = actions.artwork(video.thumb),
                        onClick = { actions.play(video.ratingKey) },
                        onOptions = { actions.options(video) },
                        width = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, width: Dp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = width, height = 44.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

/** A matching channel: avatar and name; OK opens its page. */
@Composable
private fun ChannelChip(channel: CollectionDto, actions: TvActions) {
    Surface(
        onClick = { actions.openChannel(channel) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    ) {
        Row(Modifier.padding(6.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(actions.artwork(channel.thumb), 36.dp)
            Spacer(Modifier.width(10.dp))
            Text(channel.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}
