package dev.owndemand.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owndemand.app.AppContainer
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.browse.FeedCard
import dev.owndemand.app.ui.browse.RefreshableBox
import dev.owndemand.app.ui.browse.continueWatchingFor
import dev.owndemand.app.ui.browse.recentlyWatched
import dev.owndemand.app.ui.browseColumns
import kotlinx.coroutines.launch

/**
 * Library tab (Chunk 2, user choice 2026-10-19): every part-watched video
 * (Continue watching, most recent first) and the watch history (Recently
 * watched) — the full-page version of Home's Continue-watching row. Built
 * from the in-memory library index, so it updates with every refresh and
 * every watched-state change.
 */
@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlay: (String) -> Unit,
) {
    LaunchedEffect(Unit) { container.libraryIndex.ensureLoaded(container) }
    val items by container.libraryIndex.items.collectAsStateWithLifecycle()
    val loading by container.libraryIndex.loading.collectAsStateWithLifecycle()
    val refreshing by container.libraryIndex.refreshing.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val hidden = settings?.hiddenContinue.orEmpty()
    val all = items.orEmpty()
    val inProgress = remember(all, hidden) { continueWatchingFor(all, hidden) }
    val history = remember(all, inProgress) {
        val shown = inProgress.mapTo(HashSet()) { it.ratingKey }
        recentlyWatched(all).filterNot { it.ratingKey in shown }
    }
    val scope = rememberCoroutineScope()

    val columns = LocalLayoutMode.current.browseColumns()
    val gutter = if (columns > 1) 12.dp else 0.dp
    RefreshableBox(
        refreshing = refreshing,
        onRefresh = { scope.launch { container.libraryIndex.refresh(container) } },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Continue watching", inProgress.size)
            }
            if (inProgress.isEmpty() && !loading) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyLine("Nothing in progress.") }
            }
            items(inProgress, key = { "cw-" + it.ratingKey }) { video ->
                FeedCard(
                    item = video,
                    artworkUrl = container.repository().artworkUrl(video.thumb),
                    onClick = { onPlay(video.ratingKey) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Recently watched", history.size)
            }
            if (history.isEmpty() && !loading) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyLine("Nothing watched yet.") }
            }
            items(history, key = { "rw-" + it.ratingKey }) { video ->
                FeedCard(
                    item = video,
                    artworkUrl = container.repository().artworkUrl(video.thumb),
                    onClick = { onPlay(video.ratingKey) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        if (count > 0) "$title · $count" else title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
