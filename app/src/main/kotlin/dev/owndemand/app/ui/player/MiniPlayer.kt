package dev.owndemand.app.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.owndemand.app.R
import dev.owndemand.app.playback.PlaybackManager
import dev.owndemand.app.ui.LocalLayoutMode
import dev.owndemand.app.ui.isTablet
import dev.owndemand.app.ui.miniPlayerCardWidth
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Floating miniplayer (user direction 2026-10-16, like the reference app's): a live
 * video card that floats over the shell, follows the finger when dragged,
 * snaps to the nearest of the four corners on release, and closes playback
 * when dragged fully off screen. Tap returns to the watch screen; the X
 * stops playback (position already reported via timeline).
 */
@Composable
fun FloatingMiniPlayer(
    manager: PlaybackManager,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item by manager.currentItem.collectAsStateWithLifecycle()
    val current = item ?: return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Card size scales with the window's width class (phone stays 200 dp).
    val mode = LocalLayoutMode.current
    val cardWDp = mode.miniPlayerCardWidth()
    val cardHDp = cardWDp * 9f / 16f
    val cardW = with(density) { cardWDp.toPx() }
    val cardH = with(density) { cardHDp.toPx() }
    val margin = with(density) { 12.dp.toPx() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val boundsW = constraints.maxWidth.toFloat()
        val boundsH = constraints.maxHeight.toFloat()

        // Start at the lower-right corner (reference default).
        var posX by remember(boundsW) {
            mutableFloatStateOf(boundsW - cardW - margin)
        }
        var posY by remember(boundsH) {
            mutableFloatStateOf(boundsH - cardH - margin)
        }

        fun snapOrClose() {
            val x = posX
            val y = posY
            // Center past an edge = dragged off screen → dismiss.
            val cx = x + cardW / 2f
            val cy = y + cardH / 2f
            if (cx < 0f || cx > boundsW || cy < 0f || cy > boundsH) {
                onClose()
                return
            }
            val xs = listOf(margin, boundsW - cardW - margin)
            val ys = listOf(margin, boundsH - cardH - margin)
            val tx = xs.minByOrNull { abs(it - x) } ?: x
            val ty = ys.minByOrNull { abs(it - y) } ?: y
            scope.launch {
                launch { animate(x, tx) { v, _ -> posX = v } }
                launch { animate(y, ty) { v, _ -> posY = v } }
            }
        }

        Box(
            Modifier
                .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                .width(cardWDp)
                .height(cardHDp)
                .shadow(10.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray)
                .pointerInput(boundsW, boundsH) {
                    detectDragGestures(
                        onDragEnd = { snapOrClose() },
                        onDragCancel = { snapOrClose() },
                    ) { change, drag ->
                        change.consume()
                        posX = (posX + drag.x).coerceIn(-cardW, boundsW)
                        posY = (posY + drag.y).coerceIn(-cardH, boundsH)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onExpand() }) },
        ) {
            AndroidView(
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.player_surface, null, false) as PlayerView)
                        .apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                },
                update = { it.player = manager.player },
                onRelease = { it.player = null },
                modifier = Modifier.matchParentSize(),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (mode.isTablet) 5.dp else 3.dp)
                    .size(if (mode.isTablet) 34.dp else 26.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(if (mode.isTablet) 19.dp else 15.dp),
                )
            }
        }
    }
}
