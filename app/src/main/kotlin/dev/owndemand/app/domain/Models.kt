package dev.owndemand.app.domain

/** A Plex Media Server the user can connect to. */
data class PlexServer(
    val machineIdentifier: String,
    val name: String,
    val baseUrl: String,
    /** Per-server access token from plex.tv /resources (distinct from the account token). */
    val accessToken: String,
)

/** A library section selected as the channel-video library. */
data class PlexLibrary(
    /** Numeric section key; unstable across server reinstalls — resolve via uuid. */
    val key: String,
    val uuid: String,
    val title: String,
    val type: String,
)
