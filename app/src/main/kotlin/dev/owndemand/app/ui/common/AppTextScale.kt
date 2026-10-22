package dev.owndemand.app.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The density carrying the app's Text size (system font scale × the
 * Settings slider), provided by OwnDemandAppRoot. Menus, bottom sheets and
 * dialogs open in a window of their own whose density is reset to the
 * system's, so their content re-applies this one with [AppTextScale].
 */
val LocalAppDensity = compositionLocalOf<Density?> { null }

/** Content of a popup / sheet / dialog, at the app's Text size. */
@Composable
fun AppTextScale(content: @Composable () -> Unit) {
    val appDensity = LocalAppDensity.current
    if (appDensity == null) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides appDensity, content = content)
    }
}

/** [DropdownMenu] whose items follow the app's Text size. */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
        val menu = this
        AppTextScale { menu.content() }
    }
}
