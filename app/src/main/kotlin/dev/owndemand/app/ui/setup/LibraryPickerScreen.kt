package dev.owndemand.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owndemand.app.domain.PlexLibrary
import dev.owndemand.app.ui.readableWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPickerScreen(vm: LibraryPickerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Choose your video library") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                // Same centred 640 dp column as the other setup pages.
                .readableWidth()
                .fillMaxHeight()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                LibraryPickerState.Loading -> {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator()
                }

                is LibraryPickerState.Error -> {
                    Spacer(Modifier.height(48.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.load() }) { Text("Retry") }
                }

                is LibraryPickerState.Ready -> {
                    if (s.libraries.isEmpty()) {
                        Spacer(Modifier.height(48.dp))
                        Text("This server has no libraries.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(s.libraries, key = { it.uuid }) { library ->
                                LibraryCard(library) { vm.select(library) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { vm.load() }) { Text("Refresh") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(library: PlexLibrary, onSelect: () -> Unit) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(library.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    libraryKindLabel(library.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onSelect) { Text("Use") }
        }
    }
}

/** Plex's library type in plain words (polish pass; was "type: movie"). */
fun libraryKindLabel(type: String): String = when (type) {
    "movie" -> "Movies and videos"
    "show" -> "TV shows"
    "artist" -> "Music"
    "photo" -> "Photos"
    else -> type.replaceFirstChar { it.uppercase() }
}
