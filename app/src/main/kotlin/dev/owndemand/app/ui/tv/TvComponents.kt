package dev.owndemand.app.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.owndemand.app.R
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.ui.browse.isNewVideo
import dev.owndemand.app.ui.common.displayTitle
import dev.owndemand.app.ui.common.formatDuration
import dev.owndemand.app.ui.common.formatUploadDate

/**
 * The video card that has focus right now (null: none), so the shell can
 * open its options from the remote's Menu key wherever it is.
 */
val LocalFocusedVideo = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.runtime.mutableStateOf<ItemDto?>(null) }

/** Standard 16:9 card width on Home rows (about 3½ across a TV screen). */
val TvCardWidth = 236.dp

/** Card corner radius, shared by the focus outline. */
private val CardShape = RoundedCornerShape(10.dp)

/**
 * Fixed-focus scrolling (TV-PLAN "Principles"): a focused child is always
 * scrolled to [leadingPx] from the list's start, so the focus stays put and
 * the content moves under it. The list clamps at either end by itself.
 */
@OptIn(ExperimentalFoundationApi::class)
private class PivotBringIntoViewSpec(private val leadingPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - leadingPx
}

/** Lists inside [content] keep their focused child [leading] from their start. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(leading: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { leading.toPx() }
    val spec = remember(px) { PivotBringIntoViewSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Card colours and focus cue: a slight grow plus a solid outline. */
@Composable
private fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.border),
        shape = CardShape,
    ),
)

/**
 * A video: 16:9 thumbnail (focus grows it and draws the outline) with
 * duration, New, Watched and progress overlays; title and channel · date
 * below, brighter while focused. OK plays; long-press OK or Menu opens the
 * options.
 */
@Composable
fun TvVideoCard(
    item: ItemDto,
    artworkUrl: String,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null fills the cell (grids). */
    width: Dp? = TvCardWidth,
    showChannel: Boolean = true,
    onFocused: (ItemDto) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusedVideo = LocalFocusedVideo.current
    LaunchedEffect(focused) {
        if (focused) {
            onFocused(item)
            focusedVideo.value = item
        } else if (focusedVideo.value?.ratingKey == item.ratingKey) {
            focusedVideo.value = null
        }
    }
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()),
    ) {
        Card(
            onClick = onClick,
            onLongClick = onOptions,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = cardBorder(),
            colors = CardDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            interactionSource = interaction,
        ) {
            Box(Modifier.fillMaxSize()) { VideoThumbnail(item, artworkUrl) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            displayTitle(item.title, item.channelName),
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.channelName?.takeIf { showChannel },
            formatUploadDate(item).takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Thumbnail plus its badges, filling the card. */
@Composable
private fun BoxScope.VideoThumbnail(item: ItemDto, artworkUrl: String) {
    AsyncImage(
        model = artworkUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
    if (isNewVideo(item, System.currentTimeMillis() / 1000)) {
        Pill("New", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.align(Alignment.TopStart))
    }
    if (item.isFullyWatched) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("Watched", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (item.duration > 0) {
        Pill(formatDuration(item.duration), Color.Black.copy(alpha = 0.75f), Color.White, Modifier.align(Alignment.BottomEnd))
    }
    if (item.isPartiallyWatched && item.duration > 0) {
        ProgressStrip(item.viewOffset.toFloat() / item.duration, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color, modifier: Modifier) {
    Text(
        text,
        color = content,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Watched-so-far bar along a thumbnail's foot. */
@Composable
fun ProgressStrip(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.White.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** A channel's round avatar (its collection artwork). */
@Composable
fun ChannelAvatar(url: String, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** The tile ending a channel's Home row: opens the channel page. */
@Composable
fun ViewChannelTile(
    channel: CollectionDto,
    avatarUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TvCardWidth,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = cardBorder(),
            colors = CardDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ChannelAvatar(avatarUrl, 56.dp)
                Spacer(Modifier.height(10.dp))
                Text("View channel", style = MaterialTheme.typography.titleSmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            channel.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A titled horizontal row (Home, Library): fixed focus at the row's start,
 * and coming back into the row returns to the card last focused there.
 */
@Composable
fun TvShelf(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    leading: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = TvSafeHorizontal, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let {
                it()
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        PivotScroll(leading = TvSafeHorizontal) {
            LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(horizontal = TvSafeHorizontal, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
        }
    }
}

/** A centred one-line notice (empty states, errors) in the content area. */
@Composable
fun TvNotice(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
