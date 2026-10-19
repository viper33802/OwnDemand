package dev.owndemand.app.data

import dev.owndemand.app.data.plex.ServerDiscovery
import dev.owndemand.app.data.plex.dto.ResourceConnection
import dev.owndemand.app.domain.PlexServer
import dev.owndemand.app.shouldRefreshOnReturn
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSelectionTest {

    private val home = ResourceConnection("https", "192.0.2.10", 32400, local = true,
        uri = "https://192-0-2-10.abc.plex.direct:32400")
    private val homeHttp = ResourceConnection("http", "192.0.2.10", 32400, local = true)
    private val remote = ResourceConnection("https", "198.51.100.7", 32400, local = false,
        uri = "https://198-51-100-7.abc.plex.direct:32400")
    private val relay = ResourceConnection("https", "203.0.113.9", 8443, local = false, relay = true,
        uri = "https://203-0-113-9.abc.plex.direct:8443")

    @Test
    fun `addresses are ordered home, remote, relay - https only, no duplicates`() {
        val urls = ServerDiscovery.orderedBaseUrls(listOf(relay, remote, homeHttp, home, home))
        assertEquals(listOf(home.uri, remote.uri, relay.uri), urls)
    }

    @Test
    fun `discovered server keeps the whole list and starts on the home address`() {
        val json = """
            [ { "name": "S", "provides": "server", "owned": true, "accessToken": "T",
                "clientIdentifier": "m", "connections": [
                  { "protocol": "https", "address": "203.0.113.9", "port": 8443, "local": false,
                    "relay": true, "uri": "${relay.uri}" },
                  { "protocol": "https", "address": "198.51.100.7", "port": 32400, "local": false,
                    "uri": "${remote.uri}" },
                  { "protocol": "https", "address": "192.0.2.10", "port": 32400, "local": true,
                    "uri": "${home.uri}" } ] } ]
        """.trimIndent()
        val server = ServerDiscovery.serversFromResourcesJson(json).single()
        assertEquals(home.uri, server.baseUrl)
        assertEquals(listOf(home.uri, remote.uri, relay.uri), server.connections)
    }

    @Test
    fun `older installs with a single stored address still have a candidate`() {
        val old = PlexServer("m", "S", "https://x.plex.direct:32400", "T")
        assertEquals(listOf("https://x.plex.direct:32400"), old.candidates)
    }

    @Test
    fun `home unreachable - falls back to remote`() = runTest {
        val picked = selectReachable(listOf("home", "remote", "relay")) { it != "home" }
        assertEquals("remote", picked)
    }

    @Test
    fun `home preferred even when remote answers first`() = runTest {
        val picked = selectReachable(listOf("home", "remote")) { url ->
            if (url == "home") delay(300) // slower, but reachable
            true
        }
        assertEquals("home", picked)
    }

    @Test
    fun `relay is the last resort`() = runTest {
        assertEquals("relay", selectReachable(listOf("home", "remote", "relay")) { it == "relay" })
    }

    @Test
    fun `nothing reachable - null`() = runTest {
        assertNull(selectReachable(listOf("home", "remote")) { false })
    }

    @Test
    fun `auto-refresh only after 15 minutes away`() {
        val min = 60_000L
        assertFalse(shouldRefreshOnReturn(null, 100 * min))
        assertFalse(shouldRefreshOnReturn(0, 14 * min))
        assertTrue(shouldRefreshOnReturn(0, 15 * min))
        assertTrue(shouldRefreshOnReturn(0, 120 * min))
    }
}
