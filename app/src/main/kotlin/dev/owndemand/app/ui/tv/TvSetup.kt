package dev.owndemand.app.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.owndemand.app.ui.common.BrandWordmark
import dev.owndemand.app.ui.setup.LibraryPickerState
import dev.owndemand.app.ui.setup.LibraryPickerViewModel
import dev.owndemand.app.ui.setup.SetupUiState
import dev.owndemand.app.ui.setup.SetupViewModel
import dev.owndemand.app.ui.setup.libraryKindLabel

/** Focus [requester] once it is placed (screens open with something focused). */
@Composable
fun FocusOnStart(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
}

/**
 * TV sign-in (Chunk 6): no browser needed — a short code to enter at
 * plex.tv/link on a phone or computer, where Plex itself asks for the
 * password and any two-step code. Then the server list.
 */
@Composable
fun TvConnectScreen(vm: SetupViewModel, signedIn: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BrandWordmark(Modifier.width(300.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Every video in your Plex library, ready to play.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))
            when (val s = state) {
                SetupUiState.Idle -> {
                    val first = remember { FocusRequester() }
                    FocusOnStart(first)
                    if (signedIn) {
                        Button(onClick = { vm.rediscoverServers(canOpenBrowser = false) }, modifier = Modifier.focusRequester(first)) {
                            Text("Continue — find your servers")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { vm.startSignIn(canOpenBrowser = false) }) {
                            Text("Sign in with a different account")
                        }
                    } else {
                        Button(onClick = { vm.startSignIn(canOpenBrowser = false) }, modifier = Modifier.focusRequester(first)) {
                            Text("Sign in with Plex")
                        }
                    }
                }
                is SetupUiState.AwaitingLinkCode, is SetupUiState.AwaitingBrowser -> {
                    Step("1", "On your phone or computer, go to plex.tv/link")
                    Step("2", "Sign in to Plex if asked")
                    Step("3", "Enter the code shown here")
                    Spacer(Modifier.height(24.dp))
                    val cancel = remember { FocusRequester() }
                    FocusOnStart(cancel)
                    OutlinedButton(onClick = { vm.backToIdle() }, modifier = Modifier.focusRequester(cancel)) {
                        Text("Cancel")
                    }
                }
                is SetupUiState.Working -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(s.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
                is SetupUiState.PickServer -> {
                    Text("Choose your server", style = MaterialTheme.typography.headlineSmall)
                }
                is SetupUiState.Error -> {
                    Text(s.message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(20.dp))
                    val retry = remember { FocusRequester() }
                    FocusOnStart(retry)
                    Row {
                        Button(onClick = { vm.rediscoverServers(canOpenBrowser = false) }, modifier = Modifier.focusRequester(retry)) {
                            Text("Retry")
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { vm.backToIdle() }) { Text("Back") }
                    }
                }
            }
        }
        Spacer(Modifier.width(64.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when (val s = state) {
                is SetupUiState.AwaitingLinkCode -> CodePanel(s.code)
                is SetupUiState.AwaitingBrowser -> CodePanel(s.code)
                is SetupUiState.PickServer -> {
                    val first = remember { FocusRequester() }
                    FocusOnStart(first)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(s.servers) { i, server ->
                            TvListRow(
                                title = server.name,
                                detail = "${server.candidates.size} address${if (server.candidates.size == 1) "" else "es"}",
                                onClick = { vm.selectServer(server) },
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CodePanel(code: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("plex.tv/link", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        Text(
            code,
            style = MaterialTheme.typography.displayLarge,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Waiting for approval…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** TV library picker: the server's libraries as focusable rows. */
@Composable
fun TvLibraryPicker(vm: LibraryPickerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 160.dp, vertical = 54.dp),
    ) {
        Text("Choose your video library", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The library your channel videos are in. You can change it later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            LibraryPickerState.Loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            is LibraryPickerState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                val retry = remember { FocusRequester() }
                FocusOnStart(retry)
                Button(onClick = { vm.load() }, modifier = Modifier.focusRequester(retry)) { Text("Retry") }
            }
            is LibraryPickerState.Ready -> {
                val first = remember { FocusRequester() }
                FocusOnStart(first)
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(s.libraries, key = { _, l -> l.uuid }) { i, library ->
                        TvListRow(
                            title = library.title,
                            detail = libraryKindLabel(library.type),
                            onClick = { vm.select(library) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.load() }) { Text("Refresh") }
                    }
                }
            }
        }
    }
}
