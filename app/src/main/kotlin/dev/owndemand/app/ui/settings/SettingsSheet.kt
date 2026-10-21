package dev.owndemand.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.owndemand.app.data.settings.AppSettings
import dev.owndemand.app.domain.VideoQuality
import dev.owndemand.app.ui.readableWidth

/**
 * Settings tab body: who is signed in and what they watch up top; the
 * quality toggle and actions next; diagnostics (address, client id) folded
 * away until asked for (user direction 2026-10-16). Tokens are never shown.
 */
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onSignOut: () -> Unit,
    onChangeLibrary: () -> Unit,
    onSwitchServer: () -> Unit,
    onQualityChange: (wifi: Boolean, quality: VideoQuality) -> Unit,
    onAutoplayChange: (Boolean) -> Unit,
    onPictureInPictureChange: (Boolean) -> Unit,
    onBackgroundPlayChange: (Boolean) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onSkipSegmentsChange: (Boolean) -> Unit,
    onSkipModeChange:
        (dev.owndemand.app.playback.SegmentCategory, dev.owndemand.app.playback.SkipMode) -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    var licensesOpen by remember { mutableStateOf(false) }
    // The one confirmation of the third-party contact, every time the
    // switch is moved to on (the app's only exception to "nothing but
    // Plex" — the dialog says exactly what leaves the device).
    var confirmSkipOn by remember { mutableStateOf(false) }
    Column(
        // readableWidth: at tablet widths the whole page becomes a centred
        // 640 dp column (survey A5); phones are untouched.
        modifier = Modifier
            .readableWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // Account header: the Plex user, then server · library.
        val account = settings.accountUsername
        if (account != null) {
            Text(account, style = MaterialTheme.typography.titleLarge)
        }
        val where = listOfNotNull(settings.server?.name, settings.library?.title)
            .joinToString(" · ")
        Text(
            where.ifBlank { "Not connected" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // Playback quality (Chunk 3): a maximum per network type.
        Text("Maximum quality", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Videos above the limit are converted by your Plex server; lower ones play as-is",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QualityRow("On Wi-Fi", settings.qualityWifi) { onQualityChange(true, it) }
        QualityRow("On mobile data", settings.qualityMobile) { onQualityChange(false, it) }

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Autoplay next",
            detail = "When a video ends, play the next one after a 5-second countdown " +
                "(right away when you are outside the app)",
            checked = settings.autoplay,
            onCheckedChange = onAutoplayChange,
        )

        // Playback outside the app (Chunk 4).
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Picture-in-picture",
            detail = "Leaving the app while a video plays keeps it going in a small floating window",
            checked = settings.pictureInPicture,
            onCheckedChange = onPictureInPictureChange,
        )
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Keep playing in the background",
            detail = "After you leave the app the sound keeps going, with controls in the " +
                "notification and on the lock screen",
            checked = settings.backgroundPlay,
            onCheckedChange = onBackgroundPlayChange,
        )

        // Skip segments (Chunk 10): the app's one opt-in third-party contact,
        // off by default. Enabling it runs through a confirmation dialog.
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Skip segments",
            detail = "Jump over sponsors and other marked stretches. While on, the app asks " +
                "SponsorBlock (sponsor.ajay.app) for a short hash of the video's source id — " +
                "never which video, no Plex sign-in, no server address, no device details. " +
                "Read-only: nothing is submitted or voted on. Segment data by SponsorBlock, " +
                "CC BY-NC-SA.",
            checked = settings.skipSegments,
            onCheckedChange = { on ->
                if (on) confirmSkipOn = true else onSkipSegmentsChange(false)
            },
        )
        if (settings.skipSegments) {
            Spacer(Modifier.height(8.dp))
            dev.owndemand.app.playback.SegmentCategory.entries.forEach { category ->
                SkipModeRow(
                    label = category.label.replaceFirstChar { it.uppercase() },
                    mode = settings.skipModes[category]
                        ?: dev.owndemand.app.playback.SkipMode.Off,
                ) { onSkipModeChange(category, it) }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Text size (user request 2026-10-16): multiplies the system font
        // scale app-wide; the whole screen rescales as it is dragged.
        Text("Text size", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (settings.fontScale == 1f) "Standard" else "Larger text everywhere",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            // The slider itself stays a sane touch length on big screens
            // instead of spanning ~1100 dp (survey A5).
            Modifier.fillMaxWidth().widthIn(max = 480.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("A", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.fontScale,
                onValueChange = onFontScaleChange,
                valueRange = 0.85f..1.5f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            Text("A", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onChangeLibrary,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Change library") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onSwitchServer,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Switch server") }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onSignOut,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign out and forget this device") }

        // Diagnostics stay out of the way until asked for.
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { detailsOpen = !detailsOpen }) {
            Text(if (detailsOpen) "Hide connection details" else "Connection details")
        }
        if (detailsOpen) {
            SettingRow("Address", settings.server?.baseUrl ?: "—", mono = true)
            SettingRow(
                "Client identifier",
                settings.clientIdentifier.take(8) + "…",
                mono = true,
            )
            SettingRow("Signed in", if (settings.isSignedIn) "yes" else "no")
        }

        TextButton(onClick = { licensesOpen = true }) { Text("Open-source licences") }
        val context = LocalContext.current
        val version = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
        }
        Text(
            "OwnDemand ${version.orEmpty()}".trim(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    if (licensesOpen) OpenSourceLicensesDialog(onDismiss = { licensesOpen = false })

    if (confirmSkipOn) {
        AlertDialog(
            onDismissRequest = { confirmSkipOn = false },
            title = { Text("Contact SponsorBlock?") },
            text = {
                Text(
                    "So far this app has talked to nothing but Plex. Turning Skip segments on " +
                        "adds one exception: when a video starts, the app asks SponsorBlock " +
                        "(sponsor.ajay.app) for the first 4 characters of a hash of the video's " +
                        "source id. That prefix is shared by thousands of videos, so the " +
                        "service never learns which video you are watching. No Plex sign-in, " +
                        "server address or device details are ever sent, and the app never " +
                        "submits or votes on segments. You can switch this off again at any time.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSkipOn = false
                        onSkipSegmentsChange(true)
                    },
                ) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSkipOn = false }) { Text("Cancel") }
            },
        )
    }
}

/** One segment category: label plus its behaviour, off by default. */
@Composable
private fun SkipModeRow(
    label: String,
    mode: dev.owndemand.app.playback.SkipMode,
    onChange: (dev.owndemand.app.playback.SkipMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(skipModeLabel(mode)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                dev.owndemand.app.playback.SkipMode.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(skipModeLabel(option)) },
                        onClick = {
                            onChange(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** Row text for a category's behaviour. */
fun skipModeLabel(mode: dev.owndemand.app.playback.SkipMode): String = when (mode) {
    dev.owndemand.app.playback.SkipMode.Auto -> "Skip automatically"
    dev.owndemand.app.playback.SkipMode.Button -> "Show a skip button"
    dev.owndemand.app.playback.SkipMode.Off -> "Off"
}

/**
 * NOTICE (copyright + bundled libraries) followed by the Apache 2.0 text,
 * read from the APK's assets — Apache 2.0 §4 requires both to ship with
 * the binary. LicenseAssetsTest keeps the assets in sync with the repo.
 */
@Composable
private fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("NOTICE.txt", "LICENSE.txt").joinToString("\n\n") { name ->
            runCatching {
                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.readableWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Open-source licences",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** One quality cap: label on the left, current choice opening a short list. */
@Composable
private fun QualityRow(label: String, value: VideoQuality, onChange: (VideoQuality) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(value.label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                VideoQuality.entries.forEach { q ->
                    DropdownMenuItem(
                        text = { Text(q.label) },
                        onClick = {
                            onChange(q)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
