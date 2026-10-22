package dev.owndemand.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.owndemand.app.data.plex.dto.ItemDto

/**
 * Opens the long-press menu for a video. Provided once by the shell, so
 * every video card in the app gets the same menu without plumbing.
 */
val LocalVideoMenu = staticCompositionLocalOf<((ItemDto) -> Unit)?> { null }

/** Tap plays ([onClick]); long-press opens the video menu (when provided). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.videoClickable(item: ItemDto, onClick: () -> Unit): Modifier {
    val openMenu = LocalVideoMenu.current
    return combinedClickable(
        onClick = onClick,
        onLongClick = openMenu?.let { open -> { open(item) } },
    )
}

/** Which actions the menu offers for [item] (pure, unit-tested). */
data class VideoMenuOptions(
    val markWatched: Boolean,
    val markUnwatched: Boolean,
    val removeFromContinue: Boolean,
    val goToChannel: Boolean,
)

fun videoMenuOptions(item: ItemDto, hiddenFromContinue: Boolean): VideoMenuOptions = VideoMenuOptions(
    // Watched → only "unwatched"; part-watched → both (unwatched = start over);
    // never played → only "watched".
    markWatched = !item.isFullyWatched,
    markUnwatched = item.isFullyWatched || item.viewOffset > 0,
    removeFromContinue = item.isPartiallyWatched && !hiddenFromContinue,
    goToChannel = !item.channelName.isNullOrBlank(),
)

/** The long-press menu: watched state, Continue watching, channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoActionsSheet(
    item: ItemDto,
    hiddenFromContinue: Boolean,
    onDismiss: () -> Unit,
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onRemoveFromContinue: () -> Unit,
    onGoToChannel: () -> Unit,
) {
    val options = videoMenuOptions(item, hiddenFromContinue)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AppTextScale {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                displayTitle(item.title, item.channelName),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            item.channelName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            fun row(label: String, action: () -> Unit): @Composable () -> Unit = {
                ListItem(
                    headlineContent = { Text(label) },
                    modifier = Modifier.clickable {
                        action()
                        onDismiss()
                    },
                )
            }
            if (options.markWatched) row("Mark as watched", onMarkWatched)()
            if (options.markUnwatched) {
                row(if (item.isFullyWatched) "Mark as unwatched" else "Mark as unwatched (clear progress)", onMarkUnwatched)()
            }
            if (options.removeFromContinue) row("Remove from Continue watching", onRemoveFromContinue)()
            if (options.goToChannel) row("Go to channel", onGoToChannel)()
        }
        }
    }
}
