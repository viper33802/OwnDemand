package dev.owndemand.app.ui.tv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.owndemand.app.AppContainer
import dev.owndemand.app.domain.ThemeMode
import dev.owndemand.app.domain.VideoQuality
import dev.owndemand.app.playback.SegmentCategory
import dev.owndemand.app.playback.SkipMode
import dev.owndemand.app.ui.main.MainViewModel
import dev.owndemand.app.ui.settings.skipModeLabel
import kotlinx.coroutines.launch

/** Text-size steps offered on TV (the phone slider's range, 5 % apart). */
private val TextScales = (85..150 step 5).map { it / 100f }

/** "105 %" for 1.05f (rounded, not truncated: 1.05f * 100 is 104.99…). */
private fun percent(scale: Float): String = "${kotlin.math.round(scale * 100).toInt()} %"

/** A choice panel's content: title, options with labels, current, and what to do. */
private class Choice<T>(
    val title: String,
    val options: List<T>,
    val current: T,
    val label: (T) -> String,
    val onSelect: (T) -> Unit,
)

/**
 * TV Settings (Chunk 6): the phone settings as focusable rows. Every value
 * is changed with OK — switches flip, choices open a side panel — so the
 * remote never gets stuck in a control. No mobile-data quality: TVs are on
 * Wi-Fi or Ethernet.
 */
@Composable
fun TvSettingsScreen(container: AppContainer, mainVm: MainViewModel) {
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    var choice by remember { mutableStateOf<Choice<*>?>(null) }
    var confirmSkip by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var licences by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun onOff(on: Boolean) = if (on) "On" else "Off"

    PivotScroll(leading = 140.dp) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = 64.dp, end = 160.dp, top = TvSafeVertical, bottom = 200.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    settings.accountUsername?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                    Text(
                        listOfNotNull(settings.server?.name, settings.library?.title).joinToString(" · ").ifBlank { "Not connected" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { TvSectionTitle("Appearance") }
            item {
                TvListRow(
                    title = "Theme",
                    detail = "System follows the device; a TV without a dark mode of its own shows Dark",
                    value = settings.themeMode.label,
                    onClick = {
                        choice = Choice("Theme", ThemeMode.entries, settings.themeMode, { it.label }, mainVm::setThemeMode)
                    },
                )
            }
            item {
                TvListRow(
                    title = "Text size",
                    value = if (settings.fontScale == 1f) "Standard" else percent(settings.fontScale),
                    onClick = {
                        val current = TextScales.minBy { kotlin.math.abs(it - settings.fontScale) }
                        choice = Choice(
                            "Text size",
                            TextScales,
                            current,
                            { if (it == 1f) "Standard (100 %)" else percent(it) },
                            mainVm::setFontScale,
                        )
                    },
                )
            }

            item { TvSectionTitle("Playback") }
            item {
                TvListRow(
                    title = "Maximum quality",
                    detail = "Videos above the limit are converted by your Plex server; lower ones play as-is",
                    value = settings.qualityWifi.label,
                    onClick = {
                        choice = Choice("Maximum quality", VideoQuality.entries, settings.qualityWifi, { it.label }) {
                            mainVm.setQuality(wifi = true, quality = it)
                        }
                    },
                )
            }
            item {
                TvListRow(
                    title = "Autoplay next",
                    detail = "When a video ends, play the next one after a 5-second countdown",
                    value = onOff(settings.autoplay),
                    onClick = { mainVm.setAutoplay(!settings.autoplay) },
                )
            }
            item {
                TvListRow(
                    title = "Picture-in-picture",
                    detail = "Pressing Home while a video plays keeps it going in a small window",
                    value = onOff(settings.pictureInPicture),
                    onClick = { mainVm.setPictureInPicture(!settings.pictureInPicture) },
                )
            }
            item {
                TvListRow(
                    title = "Keep playing in the background",
                    detail = "After you leave the app the sound keeps going",
                    value = onOff(settings.backgroundPlay),
                    onClick = { mainVm.setBackgroundPlay(!settings.backgroundPlay) },
                )
            }
            item {
                TvListRow(
                    title = "Skip segments",
                    detail = "Jump over sponsors and other marked stretches. While on, the app asks " +
                        "SponsorBlock (sponsor.ajay.app) for a short hash of the video's source id — never " +
                        "which video, no Plex sign-in, no server address, no device details. " +
                        "Read-only: nothing is submitted or voted on.\n" +
                        // SponsorBlock's attribution template, word for word (as on phones).
                        "Uses SponsorBlock data licensed used under CC BY-NC-SA 4.0 from " +
                        "https://sponsor.ajay.app/",
                    value = onOff(settings.skipSegments),
                    onClick = { if (settings.skipSegments) mainVm.setSkipSegments(false) else confirmSkip = true },
                )
            }
            if (settings.skipSegments) {
                SegmentCategory.entries.forEach { category ->
                    item(key = "skip-${category.name}") {
                        val mode = settings.skipModes[category] ?: SkipMode.Off
                        TvListRow(
                            title = category.label.replaceFirstChar { it.uppercase() },
                            value = skipModeLabel(mode),
                            onClick = {
                                choice = Choice(category.label.replaceFirstChar { it.uppercase() }, SkipMode.entries, mode, ::skipModeLabel) {
                                    mainVm.setSkipMode(category, it)
                                }
                            },
                            modifier = Modifier.padding(start = 32.dp),
                        )
                    }
                }
            }

            item { TvSectionTitle("Library and server") }
            item {
                TvListRow(
                    title = "Refresh library now",
                    detail = "The library also refreshes by itself when you come back after 15 minutes",
                    onClick = { scope.launch { container.libraryIndex.refresh(container) } },
                )
            }
            item { TvListRow(title = "Change library", value = settings.library?.title, onClick = mainVm::changeLibrary) }
            item { TvListRow(title = "Switch server", value = settings.server?.name, onClick = mainVm::switchServer) }
            item { TvListRow(title = "Sign out and forget this device", onClick = { confirmSignOut = true }) }

            item { TvSectionTitle("About") }
            item {
                TvListRow(
                    title = if (detailsOpen) "Hide connection details" else "Connection details",
                    detail = if (detailsOpen) {
                        "Address: ${settings.server?.baseUrl ?: "—"}\nClient identifier: ${settings.clientIdentifier.take(8)}…"
                    } else {
                        null
                    },
                    onClick = { detailsOpen = !detailsOpen },
                )
            }
            item { TvListRow(title = "Open-source licences", onClick = { licences = true }) }
            item {
                val context = LocalContext.current
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                }
                TvListRow(title = "Version", value = version ?: "—", onClick = {})
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    (choice as Choice<Any?>?)?.let { c ->
        ChoicePanel(c) { choice = null }
    }
    if (confirmSkip) {
        ConfirmDialog(
            title = "Contact SponsorBlock?",
            text = "So far this app has talked to nothing but Plex. Turning Skip segments on adds one " +
                "exception: when a video starts, the app asks SponsorBlock (sponsor.ajay.app) for the " +
                "first 4 characters of a hash of the video's source id. That prefix is shared by " +
                "thousands of videos, so the service never learns which video you are watching. No Plex " +
                "sign-in, server address or device details are ever sent, and the app never submits or " +
                "votes on segments. You can switch this off again at any time.",
            confirm = "Turn on",
            onConfirm = {
                confirmSkip = false
                mainVm.setSkipSegments(true)
            },
            onDismiss = { confirmSkip = false },
        )
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            text = "This device forgets your Plex account, server and library. Your watch progress stays on your Plex server.",
            confirm = "Sign out",
            onConfirm = {
                confirmSignOut = false
                mainVm.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
    if (licences) LicencesDialog { licences = false }
}

/** A side panel listing [choice]'s options; OK picks one and closes it. */
@Composable
private fun ChoicePanel(choice: Choice<Any?>, onDismiss: () -> Unit) {
    val current = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TvTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    modifier = Modifier.width(380.dp).fillMaxHeight(),
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = TvSafeVertical)) {
                        Text(choice.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            choice.options.forEach { option ->
                                item {
                                    val isCurrent = option == choice.current
                                    TvListRow(
                                        title = choice.label(option),
                                        value = if (isCurrent) "✓" else null,
                                        selected = isCurrent,
                                        onClick = {
                                            choice.onSelect(option)
                                            onDismiss()
                                        },
                                        modifier = if (isCurrent) Modifier.focusRequester(current) else Modifier,
                                    )
                                }
                            }
                        }
                        FocusOnStart(current)
                    }
                }
            }
        }
    }
}

/** A yes/no question; Cancel has focus first. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TvTheme {
            Surface(
                modifier = Modifier.width(600.dp),
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(28.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
                        Button(onClick = onConfirm) { Text(confirm) }
                    }
                    FocusOnStart(cancel)
                }
            }
        }
    }
}

/**
 * NOTICE and the Apache 2.0 text (Apache 2.0 §4: both ship with the
 * binary), scrolled with Up / Down.
 */
@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("NOTICE.txt", "LICENSE.txt").joinToString("\n\n") { name ->
            runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TvTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(horizontal = 96.dp, vertical = TvSafeVertical)) {
                    Text("Open-source licences", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Up / Down to scroll, Back to close",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusRequester(focus)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(320f) }; true }
                                    Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-320f) }; true }
                                    else -> false
                                }
                            }
                            .focusable()
                            .verticalScroll(scroll),
                    )
                    FocusOnStart(focus)
                }
            }
        }
    }
}
