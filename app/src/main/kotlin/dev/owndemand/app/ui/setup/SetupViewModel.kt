package dev.owndemand.app.ui.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.PlexTvClient
import dev.owndemand.app.data.plex.ServerDiscovery
import dev.owndemand.app.domain.PlexServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SetupUiState {
    data object Idle : SetupUiState
    /** PIN created; the user must open [authUrl] and approve. */
    data class AwaitingBrowser(val code: String, val authUrl: String) : SetupUiState
    /**
     * No browser on this device: the user enters [code] at plex.tv/link on
     * a phone or computer, or scans [qrUrl] (Plex's approval page for this
     * device; null when that second code could not be had).
     */
    data class AwaitingLinkCode(val code: String, val qrUrl: String? = null) : SetupUiState
    /** A network step is running (polling PIN, discovering, verifying). */
    data class Working(val label: String) : SetupUiState
    data class PickServer(val servers: List<PlexServer>) : SetupUiState
    data class Error(val message: String) : SetupUiState
}

class SetupViewModel(private val container: AppContainer) : ViewModel() {
    companion object {
        private const val TAG = "PlexSetup"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val POLL_TIMEOUT_MS = 10 * 60 * 1_000L
        private const val MAX_POLL_FAILURES = 5
    }

    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /**
     * [canOpenBrowser] false (e.g. Android TV) switches to a short code the
     * user types at plex.tv/link on another device, plus a QR code of the
     * approval link. The two need two codes from Plex: plex.tv/link takes
     * the short kind, the approval link the long ("strong") one. Whichever
     * is approved first signs in.
     */
    fun startSignIn(canOpenBrowser: Boolean) {
        pollJob?.cancel()
        _state.value = SetupUiState.Working("Requesting a sign-in code…")
        pollJob = viewModelScope.launch {
            try {
                // Guarantee the stable identifier before the first plex.tv call.
                val clientId = container.settingsRepository.ensureClientIdentifier()
                container.lazyClientId = clientId
                val pin = container.plexTvClient.createPin(strong = canOpenBrowser)
                val pinIds = mutableListOf(pin.id)
                _state.value = if (canOpenBrowser) {
                    SetupUiState.AwaitingBrowser(
                        code = pin.code,
                        authUrl = PlexTvClient.buildAuthUrl(
                            clientIdentifier = clientId,
                            code = pin.code,
                        ),
                    )
                } else {
                    // The QR code is a bonus: without it the typed code still works.
                    val linkPin = runCatching { container.plexTvClient.createPin(strong = true) }
                        .onFailure { Log.w(TAG, "no second code for the QR link", it) }
                        .getOrNull()
                    linkPin?.let { pinIds += it.id }
                    SetupUiState.AwaitingLinkCode(
                        code = pin.code,
                        qrUrl = linkPin?.let { PlexTvClient.buildAuthUrl(clientIdentifier = clientId, code = it.code) },
                    )
                }
                pollUntilClaimed(pinIds)
            } catch (e: Exception) {
                _state.value = SetupUiState.Error(userMessage(e, "Could not reach plex.tv."))
            }
        }
    }

    /** Asks about each of [pinIds] in turn until one is approved. */
    private suspend fun pollUntilClaimed(pinIds: List<Long>) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var consecutiveFailures = 0
        var turn = 0
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val pinId = pinIds[turn++ % pinIds.size]
            val pin = try {
                container.plexTvClient.pollPin(pinId).also { consecutiveFailures = 0 }
            } catch (e: Exception) {
                // Transient network/DNS blips are common while the user is still
                // opening the browser and signing in; keep polling until deadline.
                consecutiveFailures++
                Log.w(TAG, "PIN poll attempt $consecutiveFailures failed", e)
                if (consecutiveFailures >= MAX_POLL_FAILURES) {
                    _state.value = SetupUiState.Error(
                        userMessage(e, "Sign-in polling failed. Check the connection and retry."),
                    )
                    return
                }
                continue
            }
            val token = pin.authToken
            if (token != null) {
                container.settingsRepository.setAccountToken(token)
                discoverServers(token)
                return
            }
        }
        _state.value = SetupUiState.Error("Sign-in timed out. Start again when ready.")
    }

    /** Fetches the account's servers; the user always picks one explicitly. */
    fun discoverServers(token: String) {
        _state.value = SetupUiState.Working("Finding your Plex servers…")
        viewModelScope.launch {
            try {
                // Remember who signed in (Settings header). Best-effort —
                // a failure here must not block connecting.
                runCatching {
                    container.plexTvClient.account(token).username
                }.getOrNull()?.let { container.settingsRepository.setAccountUsername(it) }
                val servers = ServerDiscovery.serversFromResourcesJson(
                    container.plexTvClient.resourcesJson(token),
                )
                when {
                    servers.isEmpty() -> _state.value = SetupUiState.Error(
                        "No Plex servers found on this account, owned or shared.",
                    )
                    else -> _state.value = SetupUiState.PickServer(servers)
                }
            } catch (e: Exception) {
                Log.w(TAG, "server discovery failed", e)
                _state.value = SetupUiState.Error(
                    userMessage(e, "Could not list your servers."),
                )
            }
        }
    }

    /** Convenience for the retry path: uses the stored account token. */
    fun rediscoverServers(canOpenBrowser: Boolean) {
        viewModelScope.launch {
            val token = container.settingsRepository.settings.first().accountToken
            if (token != null) discoverServers(token) else startSignIn(canOpenBrowser)
        }
    }

    fun selectServer(server: PlexServer) {
        _state.value = SetupUiState.Working("Connecting to ${server.name}…")
        viewModelScope.launch {
            try {
                // Use whichever advertised address answers (home first), so
                // setting up away from home works too.
                val reachable = dev.owndemand.app.data.selectReachable(server.candidates) {
                    container.connections.probe(server, it)
                }
                val chosen = if (reachable != null) server.copy(baseUrl = reachable) else server
                container.pmsClient(chosen).verifyIdentity() // surfaces the real error if none answered
                container.settingsRepository.setServer(chosen)
                // Navigation advances automatically once settings.hasServer is true.
                _state.value = SetupUiState.Idle
            } catch (e: Exception) {
                Log.w(TAG, "connect to the selected server failed: ${e.javaClass.simpleName}")
                _state.value = SetupUiState.Error(
                    userMessage(e, "Could not connect to ${server.name} at ${server.baseUrl}."),
                )
            }
        }
    }

    fun backToIdle() {
        pollJob?.cancel()
        _state.value = SetupUiState.Idle
    }

    override fun onCleared() {
        pollJob?.cancel()
    }
}

/** Maps exceptions to safe, credential-free user messages. */
internal fun userMessage(e: Throwable, fallback: String): String =
    e.localizedMessage?.takeIf { it.isNotBlank() && !it.contains("Token", ignoreCase = true) }
        ?: fallback
