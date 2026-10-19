package dev.owndemand.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.owndemand.app.AppContainer
import dev.owndemand.app.R
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.browseColumns
import dev.owndemand.app.ui.browse.FeedCard
import dev.owndemand.app.ui.readableWidth
import kotlinx.coroutines.delay

/**
 * Search (M5): filters the in-memory [dev.owndemand.app.data.LibraryIndex]
 * by title and channel name. Server-side `/library/search` is not reliably
 * section-scoped (verified against a live server 2026-10-16), and a few hundred
 * items filter locally instantly, so search stays local and stays scoped
 * to the selected library (AGENTS.md §4.3).
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    onPlay: (String) -> Unit,
    onOpenChannel: (CollectionDto) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val items by container.libraryIndex.items.collectAsStateWithLifecycle()
    val collections by container.libraryIndex.collections.collectAsStateWithLifecycle()
    val loading by container.libraryIndex.loading.collectAsStateWithLifecycle()
    val error by container.libraryIndex.error.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { container.libraryIndex.ensureLoaded(container) }

    // Small debounce so typing doesn't re-filter on every keystroke; the
    // list itself is cheap, this just keeps recomposition calm.
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        delay(150)
        debounced = query
    }

    val channels = remember(debounced, collections) {
        searchChannels(collections.orEmpty(), debounced)
    }
    val results = remember(debounced, items) { searchItems(items.orEmpty(), debounced) }
    val artworkFor: (ItemDto) -> String = { container.repository().artworkUrl(it.thumb) }
    val channelArtwork: (CollectionDto) -> String = { container.repository().artworkUrl(it.thumb) }

    // T2: video results share the home grid breakpoints; channel matches
    // stay full-span rows. Gutter is 0 in the 1-column case, so the phone
    // layout is pixel-identical to before.
    val columns = LocalLayoutMode.current.browseColumns()
    val gutter = if (columns > 1) 12.dp else 0.dp

    Column(Modifier.fillMaxSize()) {
        SearchField(query = query, onQueryChange = { query = it }, onClose = onClose)

        when {
            items == null && loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null && items == null -> Text(
                error!!,
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            debounced.isBlank() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painterResource(R.drawable.ic_tab_search),
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Search this library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            channels.isEmpty() && results.isEmpty() && !loading ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "No results for “$debounced”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(gutter),
            ) {
                // Channel matches lead, video matches follow (user
                // direction 2026-10-16).
                if (channels.isNotEmpty()) {
                    item(key = "channels-header", span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel("Channels")
                    }
                    items(channels, key = { "channel-" + it.ratingKey }, span = { GridItemSpan(maxLineSpan) }) { channel ->
                        ChannelMatchRow(
                            channel = channel,
                            artworkUrl = channelArtwork(channel),
                            onClick = { onOpenChannel(channel) },
                        )
                    }
                }
                if (results.isNotEmpty()) {
                    item(key = "videos-header", span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(if (channels.isNotEmpty()) "Videos" else "")
                    }
                    items(results, key = { it.ratingKey }) { item ->
                        FeedCard(
                            item = item,
                            artworkUrl = artworkFor(item),
                            onClick = { onPlay(item.ratingKey) },
                        )
                    }
                }
                item(key = "bottom-spacer", span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

/** Case-insensitive channel (Collection) name match. */
fun searchChannels(collections: List<CollectionDto>, query: String): List<CollectionDto> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return collections.filter { it.title.lowercase().contains(q) }
}

/** Case-insensitive title + channel match; titles matching rank first. */
fun searchItems(items: List<ItemDto>, query: String): List<ItemDto> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val titleMatches = ArrayList<ItemDto>()
    val channelMatches = ArrayList<ItemDto>()
    for (item in items) {
        when {
            item.title.lowercase().contains(q) -> titleMatches += item
            item.channelName?.lowercase()?.contains(q) == true -> channelMatches += item
        }
    }
    return titleMatches + channelMatches
}

@Composable
private fun SectionLabel(text: String) {
    if (text.isEmpty()) return
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Search result row for a channel: composite art + name + video count. */
@Composable
private fun ChannelMatchRow(
    channel: CollectionDto,
    artworkUrl: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = channel.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 44.dp, height = 44.dp)
                .clip(CircleShape)
                .background(Color.DarkGray),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${channel.childCount} videos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    // Keyboard pops up on open (reference behavior) and hides on Search so
    // the results are visible (user direction 2026-10-16).
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        // Capped + centred on tablet widths (plan §3.3); phones unchanged.
        Modifier
            .readableWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(
                painterResource(R.drawable.ic_back),
                "Close search",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_tab_search),
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Search videos and channels",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
