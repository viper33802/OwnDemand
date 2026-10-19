package dev.owndemand.app.data

import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.PlexJson
import dev.owndemand.app.data.plex.ServerDiscovery
import dev.owndemand.app.data.plex.dto.IdentityResponse
import dev.owndemand.app.domain.PlexServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Keeps the app on a reachable address for the selected server.
 *
 * Plex advertises several HTTPS addresses per server (home network, remote,
 * relay). Away from home the home address is unreachable, so [ensureReachable]
 * probes the candidates (`/identity`, short timeout, all at once) and switches
 * to the best one that answers as the right server. It runs before library
 * loads, after network changes, after request failures and when the app
 * returns from the background. [refreshFromPlexTv] re-reads the address list
 * itself (home internet addresses change).
 */
class ConnectionManager(
    private val container: AppContainer,
    http: OkHttpClient,
) {
    private val probeClient: OkHttpClient = http.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS)
        .build()

    private val mutex = Mutex()
    private var lastCheckedAt = 0L
    private var lastCheckedUrl: String? = null

    /**
     * Makes sure the address in use answers; switches to the best reachable
     * one otherwise. Skips the probe when the current address was confirmed
     * within [maxAgeMs]. Returns true when the address changed.
     */
    suspend fun ensureReachable(maxAgeMs: Long = RECHECK_AFTER_MS): Boolean = mutex.withLock {
        val server = container.currentServer ?: return false
        val now = System.currentTimeMillis()
        if (lastCheckedUrl == server.baseUrl && now - lastCheckedAt < maxAgeMs) return false
        val chosen = selectReachable(server.candidates) { probe(server, it) } ?: return false
        lastCheckedUrl = chosen
        lastCheckedAt = System.currentTimeMillis()
        if (chosen == server.baseUrl) return false
        switchTo(server, chosen)
        true
    }

    /**
     * Re-reads the server's addresses (and access token) from plex.tv and
     * stores them. Best-effort: offline or signed out, nothing changes.
     */
    suspend fun refreshFromPlexTv() {
        val server = container.currentServer ?: return
        val token = container.settingsRepository.currentAccountToken() ?: return
        val fresh = runCatching {
            ServerDiscovery.serversFromResourcesJson(container.plexTvClient.resourcesJson(token))
        }.getOrNull()?.firstOrNull { it.machineIdentifier == server.machineIdentifier } ?: return
        if (fresh.connections == server.connections && fresh.accessToken == server.accessToken) return
        container.settingsRepository.updateServerConnections(fresh.connections, fresh.accessToken)
        mutex.withLock { lastCheckedUrl = null } // re-probe against the new list
    }

    private suspend fun switchTo(server: PlexServer, baseUrl: String) {
        container.currentServer = server.copy(baseUrl = baseUrl)
        container.settingsRepository.setServerAddress(baseUrl)
    }

    /**
     * True when [baseUrl] answers `/identity` as this server. Cancellable:
     * once a better address has answered, the remaining calls are aborted
     * instead of running to their timeout.
     */
    suspend fun probe(server: PlexServer, baseUrl: String): Boolean {
        val url = "$baseUrl/identity".toHttpUrlOrNull() ?: return false
        val request = Request.Builder()
            .url(url)
            .header("X-Plex-Token", server.accessToken)
            .build()
        val call = probeClient.newCall(request)
        val response = suspendCancellableCoroutine<Response?> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(null)
                override fun onResponse(call: Call, response: Response) = cont.resume(response)
            })
        } ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                response.use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val id = PlexJson.decodeFromString<IdentityResponse>(resp.body?.string().orEmpty())
                        .container.machineIdentifier
                    id.isBlank() || id == server.machineIdentifier
                }
            }.getOrDefault(false)
        }
    }

    companion object {
        /** Per-address probe timeout: long enough for a relay, short enough to feel quick. */
        const val PROBE_TIMEOUT_MS = 4_000L

        /** A confirmed address is trusted this long before routine re-checks. */
        const val RECHECK_AFTER_MS = 60_000L
    }
}

/**
 * Probes all [candidates] at once and returns the best-ranked one that
 * answers (list order = preference), or null when none does. A slow or
 * dead preferred address therefore costs at most one probe timeout.
 */
suspend fun selectReachable(
    candidates: List<String>,
    probe: suspend (String) -> Boolean,
): String? = coroutineScope {
    val results = candidates.map { url -> async { probe(url) } }
    var chosen: String? = null
    for ((i, result) in results.withIndex()) {
        if (result.await()) { chosen = candidates[i]; break }
    }
    results.forEach { it.cancel() }
    chosen
}
