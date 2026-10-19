package dev.owndemand.app.data.plex.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** POST/GET https://plex.tv/api/v2/pins — sign-in PIN. */
@Serializable
data class PinResponse(
    val id: Long = 0,
    val code: String = "",
    val authToken: String? = null,
)

/** GET https://plex.tv/api/v2/user — account validation. */
@Serializable
data class AccountResponse(
    val id: Long = 0,
    val uuid: String = "",
    val username: String? = null,
    val title: String? = null,
)

/**
 * GET https://clients.plex.tv/api/v2/resources — the account's devices.
 * Servers are entries whose `provides` contains "server"; their
 * machineIdentifier is carried in `clientIdentifier`.
 */
@Serializable
data class ResourceDevice(
    val name: String = "",
    val product: String = "",
    val provides: String = "",
    val owned: Boolean = false,
    val accessToken: String? = null,
    val clientIdentifier: String = "",
    val connections: List<ResourceConnection> = emptyList(),
)

@Serializable
data class ResourceConnection(
    val protocol: String = "http",
    val address: String = "",
    val port: Int = 0,
    val local: Boolean = false,
    /**
     * Ready-to-use base URL from Plex, e.g.
     * `https://192-0-2-10.<serverToken>.plex.direct:32400`. The hostname
     * encodes the address, resolves (via Plex DNS) to it, and matches the
     * server's TLS certificate — always prefer it over address:port.
     */
    val uri: String = "",
)

/** GET {server}/identity */
@Serializable
data class IdentityResponse(
    @SerialName("MediaContainer") val container: IdentityContainer = IdentityContainer(),
)

@Serializable
data class IdentityContainer(
    val machineIdentifier: String = "",
    val version: String = "",
    val friendlyName: String? = null,
)

/** GET {server}/library/sections */
@Serializable
data class SectionsResponse(
    @SerialName("MediaContainer") val container: SectionsContainer = SectionsContainer(),
)

@Serializable
data class SectionsContainer(
    val Directory: List<SectionDto> = emptyList(),
)

@Serializable
data class SectionDto(
    val key: String = "",
    val title: String = "",
    val type: String = "",
    val uuid: String = "",
    val agent: String? = null,
    val scanner: String? = null,
)
