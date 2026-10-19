package dev.owndemand.app

import androidx.compose.runtime.staticCompositionLocalOf

/** App-wide [AppContainer], provided by MainActivity's content. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
