package dev.owndemand.app.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.owndemand.app.R
import dev.owndemand.app.playback.VideoChapter
import dev.owndemand.app.ui.common.formatDuration

/** "Chapters" title with an × (Chunk 9). */
@Composable
fun ChaptersHeader(count: Int, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Chapters · $count",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Close chapters")
        }
    }
}

/**
 * Every chapter with its Plex thumbnail, title and start time; the one
 * playing is highlighted, and tapping one jumps there (Chunk 9).
 */
@Composable
fun ChapterList(
    chapters: List<VideoChapter>,
    currentIndex: Int,
    artworkFor: (String) -> String,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Open on the chapter playing now (one above it stays in view).
    LaunchedEffect(Unit) {
        if (currentIndex > 0) listState.scrollToItem(currentIndex - 1)
    }
    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(chapters, key = { i, c -> "$i-${c.startMs}" }) { i, chapter ->
            val current = i == currentIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    )
                    .clickable { onSeek(chapter.startMs) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = chapter.thumb.takeIf { it.isNotBlank() }?.let(artworkFor),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.DarkGray),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDuration(chapter.startMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}
