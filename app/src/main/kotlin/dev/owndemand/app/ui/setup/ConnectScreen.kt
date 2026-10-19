package dev.owndemand.app.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owndemand.app.domain.PlexServer
import dev.owndemand.app.ui.common.BrandWordmark
import dev.owndemand.app.ui.readableWidth

/**
 * Sign-in + server selection. Driven entirely by [SetupUiState]; the final
 * navigation happens because settings change, not from this screen.
 * [signedIn] offers a "continue" path so a stored account token does not
 * force a fresh PIN sign-in.
 */
@Composable
fun ConnectScreen(vm: SetupViewModel, signedIn: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Open the Auth App in the browser once per AwaitingBrowser state.
    if (state is SetupUiState.AwaitingBrowser) {
        val authUrl = (state as SetupUiState.AwaitingBrowser).authUrl
        LaunchedEffect(authUrl) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                // Forms stay a readable centred column on tablet widths
                // (survey A5); phones unchanged.
                .readableWidth()
                .fillMaxHeight()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandWordmark(modifier = Modifier.width(300.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "Every video in your Plex library, ready to play.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            when (val s = state) {
                SetupUiState.Idle -> {
                    if (signedIn) {
                        Button(
                            onClick = { vm.rediscoverServers() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Continue — find your servers") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.startSignIn() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with a different account") }
                    } else {
                        Button(
                            onClick = { vm.startSignIn() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with Plex") }
                    }
                }

                is SetupUiState.AwaitingBrowser -> {
                    Text(
                        "Approve the sign-in request in your browser.\n" +
                            "Code: ${s.code}",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { vm.backToIdle() }) { Text("Cancel") }
                }

                is SetupUiState.Working -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(s.label)
                }

                is SetupUiState.PickServer -> {
                    Text("Choose your server", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    s.servers.forEach { server ->
                        ServerCard(server = server, onSelect = { vm.selectServer(server) })
                        Spacer(Modifier.height(8.dp))
                    }
                }

                is SetupUiState.Error -> {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        OutlinedButton(onClick = { vm.backToIdle() }) { Text("Back") }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { vm.rediscoverServers() }) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(server: PlexServer, onSelect: () -> Unit) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    server.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onSelect) { Text("Connect") }
        }
    }
}
