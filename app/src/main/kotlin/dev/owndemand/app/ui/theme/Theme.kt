package dev.owndemand.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed brand schemes rather than Material You dynamics: OwnDemand should
 * look the same on every device (open-source audience) and read as a
 * video app — neutral surfaces, the media-tape violet accent
 * (docs/OwnDemand_Media_Tape_Assets/PALETTE.txt), reference-app dark greys.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5), // Cassette Violet
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFE5E5E5),
    onSecondaryContainer = Color(0xFF0F0F0F),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F0F0F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF606060),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA78BFA), // Lavender (onPlum text on filled controls)
    onPrimary = Color(0xFF24104F), // Deep Plum
    secondaryContainer = Color(0xFF272727),
    onSecondaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF212121),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFFAAAAAA),
)

@Composable
fun OwnDemandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
