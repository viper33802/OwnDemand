package dev.owndemand.app.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.owndemand.app.R
import dev.owndemand.app.ui.common.QrImage
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
 * TV sign-in (Chunk 6): no browser needed. A QR code of Plex's approval
 * page for this TV (scan, sign in if asked, approve) and, beside it, a
 * short code to enter at plex.tv/link on any phone or computer; Plex
 * itself asks for the password and any two-step code. Then the server
 * list.
 */
@Composable
fun TvConnectScreen(vm: SetupViewModel, signedIn: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is SetupUiState.AwaitingLinkCode -> LinkCodeScreen(s.code, s.qrUrl, onCancel = vm::backToIdle)
        is SetupUiState.AwaitingBrowser -> LinkCodeScreen(s.code, s.authUrl, onCancel = vm::backToIdle)
        else -> Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 96.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandBlock(logoSize = 168.dp)
            Spacer(Modifier.height(40.dp))
            Column(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (s) {
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
                    is SetupUiState.Working -> Waiting(s.label)
                    is SetupUiState.PickServer -> {
                        Text("Choose a server", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        val first = remember { FocusRequester() }
                        FocusOnStart(first)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(s.servers) { i, server ->
                                TvListRow(
                                    title = server.name,
                                    detail = listOfNotNull(
                                        server.sharedLabel,
                                        "${server.candidates.size} address${if (server.candidates.size == 1) "" else "es"}",
                                    ).joinToString(" · "),
                                    onClick = { vm.selectServer(server) },
                                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                )
                            }
                        }
                    }
                    is SetupUiState.Error -> {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
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
                    else -> Unit
                }
            }
        }
    }
}

/** The tape logo over the wordmark and the one-line pitch. */
@Composable
private fun BrandBlock(logoSize: Dp, alignment: Alignment.Horizontal = Alignment.CenterHorizontally) {
    Column(horizontalAlignment = alignment) {
        Image(painterResource(R.drawable.brand_tape), contentDescription = null, modifier = Modifier.size(logoSize))
        Spacer(Modifier.height(logoSize / 8))
        BrandWordmark(Modifier.width(logoSize * 1.9f))
        Spacer(Modifier.height(10.dp))
        Text(
            "Every video in your Plex library, ready to play.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A spinner (square, so it turns on its centre) and what is going on. */
@Composable
private fun Waiting(label: String, size: Dp = 28.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(size),
            strokeWidth = size / 9,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Waiting for approval: the brand and the two ways to approve on the left,
 * the QR code on the right. Sized for 960 × 540 dp (a TV at 1080p).
 * Cancel has focus (the only thing to press).
 */
@Composable
private fun LinkCodeScreen(code: String, qrUrl: String?, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.brand_tape), contentDescription = null, modifier = Modifier.size(72.dp))
                Spacer(Modifier.width(16.dp))
                BrandWordmark(Modifier.width(220.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("Sign in with Plex", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            if (qrUrl != null) {
                Step("1", "Scan the code with your phone's camera")
                Step("2", "Sign in to Plex if asked, then approve")
                Spacer(Modifier.height(18.dp))
                // The typed code stays as a fallback: a computer, or a
                // phone that cannot scan.
                Text(
                    buildAnnotatedString {
                        append("No camera? Go to plex.tv/link and enter  ")
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 3.sp,
                            ),
                        ) { append(code) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "On your phone or computer, go to plex.tv/link and enter",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    code,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 8.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cancel = remember { FocusRequester() }
                FocusOnStart(cancel)
                OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
                Spacer(Modifier.width(24.dp))
                Waiting("Waiting for approval…", size = 20.dp)
            }
        }
        Spacer(Modifier.width(48.dp))
        if (qrUrl != null) {
            // About a third of the screen width: scannable from a sofa
            // (roughly ten times the code's width away) on a mid-size TV.
            Box(
                Modifier
                    .size(QR_CARD_SIZE)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White)
                    .padding(8.dp),
            ) {
                QrImage(
                    text = qrUrl,
                    color = BrandViolet,
                    background = Color.White,
                    logo = painterResource(R.drawable.brand_tape_mark),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Image(painterResource(R.drawable.brand_tape), contentDescription = null, modifier = Modifier.size(260.dp))
        }
    }
}

/** The sign-in QR code's white card. */
private val QR_CARD_SIZE = 300.dp

/** Cassette Violet (docs/OwnDemand_Media_Tape_Assets/PALETTE.txt). */
private val BrandViolet = Color(0xFF4F46E5)

@Composable
private fun Step(number: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
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
            LibraryPickerState.Loading -> Waiting("Loading libraries…")
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
