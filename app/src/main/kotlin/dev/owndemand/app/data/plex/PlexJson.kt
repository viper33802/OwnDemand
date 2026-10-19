package dev.owndemand.app.data.plex

import kotlinx.serialization.json.Json

/** Shared JSON configuration for all Plex API payloads. */
val PlexJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
