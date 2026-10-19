package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.ResourceConnection
import dev.owndemand.app.data.plex.dto.ResourceDevice
import dev.owndemand.app.domain.PlexServer

/**
 * Pure logic for turning the plex.tv /resources payload into connectable
 * servers. Kept free of Android/OkHttp so it is unit-testable on the JVM.
 */
object ServerDiscovery {

    /**
     * HTTPS only: the server token travels with every request, so plain-HTTP
     * connections are never used (the app also forbids cleartext at the
     * platform level — see network_security_config.xml). Relay entries have
     * no usable address:port and are dropped. Preference: local, then remote.
     */
    fun pickConnection(connections: List<ResourceConnection>): ResourceConnection? =
        connections
            .filter { it.address.isNotBlank() && it.port > 0 }
            .filter { connectionBaseUrl(it).startsWith("https://") }
            .sortedByDescending { it.local }
            .firstOrNull()

    /**
     * Base URL for a connection: Plex's own `uri` when present (its
     * `<dashed-ip>.<serverToken>.plex.direct` hostname is the only name the
     * server's TLS certificate covers, and Plex DNS resolves it to the right
     * address from anywhere), else protocol://address:port.
     */
    fun connectionBaseUrl(connection: ResourceConnection): String =
        connection.uri.ifBlank {
            "${connection.protocol}://${connection.address}:${connection.port}"
        }

    fun serversFromResources(devices: List<ResourceDevice>): List<PlexServer> =
        devices
            .filter { it.owned && it.provides.split(',').contains("server") }
            .mapNotNull { device ->
                val connection = pickConnection(device.connections) ?: return@mapNotNull null
                PlexServer(
                    machineIdentifier = device.clientIdentifier,
                    name = device.name,
                    baseUrl = connectionBaseUrl(connection),
                    accessToken = device.accessToken ?: "",
                )
            }

    /** Parses the /resources JSON body (a top-level array). */
    fun parseResources(json: String): List<ResourceDevice> =
        PlexJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(
            kotlinx.serialization.serializer<ResourceDevice>(),
        ), json)

    fun serversFromResourcesJson(json: String): List<PlexServer> =
        serversFromResources(parseResources(json))
}
