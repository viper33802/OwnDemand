package dev.owndemand.app.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owndemand.app.AppContainer
import dev.owndemand.app.ui.browse.continueWatchingFor
import dev.owndemand.app.ui.browse.recentlyWatched

/**
 * TV Library (Chunk 6): everything in progress, then the watch history,
 * as one 4-across grid (the phone Library tab's content).
 */
@Composable
fun TvLibraryScreen(container: AppContainer, actions: TvActions) {
    LaunchedEffect(Unit) { container.libraryIndex.ensureLoaded(container) }
    val items by container.libraryIndex.items.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val hidden = settings?.hiddenContinue.orEmpty()
    val all = items.orEmpty()
    val inProgress = remember(all, hidden) { continueWatchingFor(all, hidden) }
    val history = remember(all, inProgress) {
        val shown = inProgress.mapTo(HashSet()) { it.ratingKey }
        recentlyWatched(all).filterNot { it.ratingKey in shown }
    }
    PivotScroll(leading = 60.dp) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(horizontal = TvSafeHorizontal, vertical = TvSafeVertical),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                TvSectionTitle(if (inProgress.isEmpty()) "Continue watching" else "Continue watching · ${inProgress.size}")
            }
            if (inProgress.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Empty("Nothing in progress.") }
            }
            items(inProgress, key = { "cw-" + it.ratingKey }) { video ->
                TvVideoCard(
                    item = video,
                    artworkUrl = actions.artwork(video.thumb),
                    onClick = { actions.play(video.ratingKey) },
                    onOptions = { actions.options(video) },
                    width = null,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                TvSectionTitle(if (history.isEmpty()) "Recently watched" else "Recently watched · ${history.size}")
            }
            if (history.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Empty("Nothing watched yet.") }
            }
            items(history, key = { "rw-" + it.ratingKey }) { video ->
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

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
