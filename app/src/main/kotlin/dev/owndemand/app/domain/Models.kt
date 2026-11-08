package dev.owndemand.app.domain

/** A Plex Media Server the user can connect to. */
data class PlexServer(
    val machineIdentifier: String,
    val name: String,
    val baseUrl: String,
    /** Per-server access token from plex.tv /resources (distinct from the account token). */
    val accessToken: String,
    /**
     * Every HTTPS address Plex advertises for this server, in preference
     * order (home network, then remote, then Plex relay). [baseUrl] is the
     * one currently in use; see ConnectionManager.
     */
    val connections: List<String> = emptyList(),
    /**
     * Who shared this server with the account; null for the account's own
     * servers. Shown in the server picker only, not stored.
     */
    val sharedBy: String? = null,
) {
    /** "Shared by ‹owner›" for a shared server, null for the account's own. */
    val sharedLabel: String?
        get() = sharedBy?.let { if (it.isBlank()) "Shared with you" else "Shared by $it" }

    /** Addresses to try, best first (older installs stored only [baseUrl]). */
    val candidates: List<String> get() = connections.ifEmpty { listOf(baseUrl) }
}

/** A library section selected as the channel-video library. */
data class PlexLibrary(
    /** Numeric section key; unstable across server reinstalls — resolve via uuid. */
    val key: String,
    val uuid: String,
    val title: String,
    val type: String,
)
