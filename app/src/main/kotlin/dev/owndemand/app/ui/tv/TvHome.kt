package dev.owndemand.app.ui.tv

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.common.displayTitle
import dev.owndemand.app.ui.common.formatDuration
import dev.owndemand.app.ui.common.formatUploadDate
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One shuffle per app run: "For you" stays put while browsing. */
private val TvSessionSeed = Random.nextInt()

/**
 * TV Home (Chunk 6): a large header describing the focused video, and the
 * library as rows below it — see [tvHomeRows].
 */
@Composable
fun TvHomeScreen(container: AppContainer, actions: TvActions) {
    LaunchedEffect(Unit) { container.libraryIndex.ensureLoaded(container) }
    val index = container.libraryIndex
    val items by index.items.collectAsStateWithLifecycle()
    val collections by index.collections.collectAsStateWithLifecycle()
    val loading by index.loading.collectAsStateWithLifecycle()
    val error by index.error.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val hidden = settings?.hiddenContinue.orEmpty()
    val rows = remember(items, collections, hidden) {
        tvHomeRows(
            items.orEmpty(),
            collections.orEmpty(),
            hidden,
            System.currentTimeMillis() / 1000,
            TvSessionSeed,
        )
    }
    val scope = rememberCoroutineScope()

    when {
        items == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        items == null && error != null -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Could not load the library.", style = MaterialTheme.typography.titleLarge)
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                index.invalidate()
                scope.launch { index.ensureLoaded(container) }
            }) { Text("Retry") }
        }
        rows.isEmpty() -> TvNotice("This library has no videos yet.")
        else -> {
            var hero by remember { mutableStateOf<ItemDto?>(null) }
            val shown = hero ?: rows.first().items.first()
            Box(Modifier.fillMaxSize()) {
                HeroBackdrop(actions.artwork(shown.thumb))
                Column(Modifier.fillMaxSize()) {
                    HeroText(shown)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                    PivotScroll(leading = 40.dp) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(rows, key = { it.key }) { row ->
                                TvShelf(
                                    title = row.title,
                                    leading = row.channel?.let { ch ->
                                        { ChannelAvatar(actions.artwork(ch.thumb), 28.dp) }
                                    },
                                ) {
                                    items(row.items, key = { it.ratingKey }) { video ->
                                        TvVideoCard(
                                            item = video,
                                            artworkUrl = actions.artwork(video.thumb),
                                            onClick = { actions.play(video.ratingKey) },
                                            onOptions = { actions.options(video) },
                                            showChannel = row.channel == null,
                                            onFocused = { hero = it },
                                        )
                                    }
                                    row.channel?.let { channel ->
                                        item(key = "view-${channel.ratingKey}") {
                                            ViewChannelTile(
                                                channel = channel,
                                                avatarUrl = actions.artwork(channel.thumb),
                                                onClick = { actions.openChannel(channel) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

/** Height of the header above the rows. */
private val HeroHeight = 210.dp

/** The focused video's artwork across the top right, fading into the page. */
@Composable
private fun HeroBackdrop(url: String) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .height(HeroHeight + 90.dp),
    ) {
        Crossfade(
            targetState = url,
            label = "hero",
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.62f)
                .align(Alignment.TopEnd),
        ) { target ->
            AsyncImage(
                model = target,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Fade left into the text column and down into the rows.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(0.30f to bg, 0.62f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to bg)),
        )
    }
}

/** Channel, title, date · length · progress, and the description's start. */
@Composable
private fun HeroText(item: ItemDto) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .padding(start = TvSafeHorizontal, top = TvSafeVertical, end = 380.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        item.channelName?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        Text(
            displayTitle(item.title, item.channelName),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            val meta = buildList {
                formatUploadDate(item).takeIf { it.isNotBlank() }?.let(::add)
                if (item.duration > 0) add(formatDuration(item.duration))
                if (item.isPartiallyWatched) add("${formatDuration(item.duration - item.viewOffset)} left")
                if (item.isFullyWatched) add("Watched")
            }.joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                item.summary.replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(520.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}
