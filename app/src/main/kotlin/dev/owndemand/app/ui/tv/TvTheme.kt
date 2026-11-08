package dev.owndemand.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import dev.owndemand.app.ui.theme.LocalDarkTheme

/*
 * TV colours (Chunk 6): the same brand as the phone schemes (Theme.kt) —
 * neutral greys, the violet accent — with the focus outline in the
 * strongest contrast colour of each scheme, readable from the couch.
 */

private val TvDark = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF24104F),
    primaryContainer = Color(0xFF3B2A78),
    onPrimaryContainer = Color(0xFFEDE7FF),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFAAAAAA),
    inverseSurface = Color(0xFFF1F1F1),
    inverseOnSurface = Color(0xFF0F0F0F),
    border = Color(0xFFFFFFFF),
    borderVariant = Color(0xFF3A3A3A),
)

private val TvLight = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E1FF),
    onPrimaryContainer = Color(0xFF1A1360),
    secondaryContainer = Color(0xFFE5E5E5),
    onSecondaryContainer = Color(0xFF0F0F0F),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF0F0F0F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5F5F5F),
    inverseSurface = Color(0xFF1C1C1C),
    inverseOnSurface = Color(0xFFF1F1F1),
    border = Color(0xFF0F0F0F),
    borderVariant = Color(0xFFD0D0D0),
)

/** TV Material theme for the resolved Theme setting (see MainActivity). */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (LocalDarkTheme.current) TvDark else TvLight,
        content = content,
    )
}

/** Overscan-safe margins: content stays inside 5 % of each edge. */
val TvSafeHorizontal = 48.dp
val TvSafeVertical = 27.dp

/** Collapsed side-rail width; screens start right of it. */
val TvRailWidth = 72.dp
