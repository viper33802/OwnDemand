package dev.owndemand.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.owndemand.app.R

/**
 * The OwnDemand wordmark from the media-tape asset set
 * (docs/OwnDemand_Media_Tape_Assets). The primary (violet) text is for
 * light surfaces; the white cut-out is for the dark scheme, picked here so
 * the mark never disappears against the background (survey B1 lesson).
 */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(
            if (isSystemInDarkTheme()) R.drawable.brand_wordmark
            else R.drawable.brand_wordmark_light,
        ),
        contentDescription = "OwnDemand",
        modifier = modifier,
    )
}
