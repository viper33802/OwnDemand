package dev.owndemand.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.owndemand.app.data.settings.AppSettings
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
    onDataSaverChange: (Boolean) -> Unit,
    onFontScaleChange: (Float) -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
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

        // Playback quality (M6).
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Data saver", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Stream everything transcoded to 480p instead of original quality",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.dataSaver, onCheckedChange = onDataSaverChange)
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
