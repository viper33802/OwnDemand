package dev.owndemand.app.data.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDiscoveryTest {

    private val resourcesJson = """
        [
          {
            "name": "Test Server",
            "product": "Plex Media Server",
            "provides": "server,player,player-pv",
            "owned": true,
            "accessToken": "SERVER-TOKEN",
            "clientIdentifier": "machine-abc",
            "connections": [
              { "protocol": "https", "address": "198.51.100.7", "port": 32400, "local": false,
                "uri": "https://198-51-100-7.token123.plex.direct:32400" },
              { "protocol": "http",  "address": "192.0.2.10",   "port": 32400, "local": true },
              { "protocol": "https", "address": "192.0.2.10",   "port": 32400, "local": true,
                "uri": "https://192-0-2-10.token123.plex.direct:32400" }
            ]
          },
          {
            "name": "Friend Server",
            "product": "Plex Media Server",
            "provides": "server",
            "owned": false,
            "sourceTitle": "friend",
            "accessToken": "OTHER",
            "clientIdentifier": "machine-xyz",
            "connections": [ { "protocol": "https", "address": "203.0.113.5", "port": 32400, "local": false } ]
          },
          {
            "name": "Test Phone",
            "product": "Plex for Android",
            "provides": "player",
            "owned": true,
            "clientIdentifier": "player-1",
            "connections": []
          }
        ]
    """.trimIndent()

    @Test
    fun `owned and shared servers are returned, owned first`() {
        val servers = ServerDiscovery.serversFromResourcesJson(resourcesJson)
        assertEquals(listOf("Test Server", "Friend Server"), servers.map { it.name })
        assertEquals("machine-abc", servers[0].machineIdentifier)
        assertEquals("SERVER-TOKEN", servers[0].accessToken)
        assertNull(servers[0].sharedBy)
        assertNull(servers[0].sharedLabel)
        // A shared server keeps its own (shared-user) token and its owner's name.
        assertEquals("OTHER", servers[1].accessToken)
        assertEquals("friend", servers[1].sharedBy)
        assertEquals("Shared by friend", servers[1].sharedLabel)
    }

    @Test
    fun `players are never offered as servers`() {
        val servers = ServerDiscovery.serversFromResourcesJson(resourcesJson)
        assertTrue(servers.none { it.machineIdentifier == "player-1" })
    }

    @Test
    fun `shared servers sort after owned ones by name, owner name optional`() {
        val json = """
            [
              { "name": "zeta", "provides": "server", "owned": false, "accessToken": "A",
                "clientIdentifier": "s1", "connections": [
                  { "protocol": "https", "address": "203.0.113.1", "port": 32400 } ] },
              { "name": "Alpha", "provides": "server", "owned": false, "sourceTitle": "pat",
                "accessToken": "B", "clientIdentifier": "s2", "connections": [
                  { "protocol": "https", "address": "203.0.113.2", "port": 32400 } ] },
              { "name": "Mine", "provides": "server", "owned": true, "accessToken": "C",
                "clientIdentifier": "o1", "connections": [
                  { "protocol": "https", "address": "203.0.113.3", "port": 32400 } ] }
            ]
        """.trimIndent()
        val servers = ServerDiscovery.serversFromResourcesJson(json)
        assertEquals(listOf("Mine", "Alpha", "zeta"), servers.map { it.name })
        assertEquals("Shared by pat", servers[1].sharedLabel)
        assertEquals("Shared with you", servers[2].sharedLabel)
    }

    @Test
    fun `relay addresses are kept, last`() {
        val json = """
            [ { "name": "Remote friend", "provides": "server", "owned": false, "accessToken": "T",
                "clientIdentifier": "m", "connections": [
                  { "protocol": "https", "address": "198.51.100.9", "port": 8443, "relay": true,
                    "uri": "https://198-51-100-9.relay.plex.direct:8443" },
                  { "protocol": "https", "address": "203.0.113.9", "port": 32400,
                    "uri": "https://203-0-113-9.t.plex.direct:32400" } ] } ]
        """.trimIndent()
        val server = ServerDiscovery.serversFromResourcesJson(json).single()
        assertEquals(
            listOf("https://203-0-113-9.t.plex.direct:32400", "https://198-51-100-9.relay.plex.direct:8443"),
            server.connections,
        )
    }

    @Test
    fun `local https plex-direct uri is preferred as base url`() {
        val servers = ServerDiscovery.serversFromResourcesJson(resourcesJson)
        // Plex's uri is used verbatim: its hostname matches the server cert.
        assertEquals("https://192-0-2-10.token123.plex.direct:32400", servers[0].baseUrl)
    }

    @Test
    fun `connection preference order`() {
        val remoteHttps = dev.owndemand.app.data.plex.dto.ResourceConnection("https", "1.2.3.4", 32400, false)
        val localHttp = dev.owndemand.app.data.plex.dto.ResourceConnection("http", "1.2.3.4", 32400, true)
        val localHttps = dev.owndemand.app.data.plex.dto.ResourceConnection("https", "1.2.3.4", 32400, true)
        assertEquals(localHttps, ServerDiscovery.pickConnection(listOf(remoteHttps, localHttp, localHttps)))
        // HTTPS only: a remote https connection beats a local plain-http one.
        assertEquals(remoteHttps, ServerDiscovery.pickConnection(listOf(remoteHttps, localHttp)))
        assertEquals(remoteHttps, ServerDiscovery.pickConnection(listOf(remoteHttps)))
        assertNull(ServerDiscovery.pickConnection(emptyList()))
    }

    @Test
    fun `plain http connections are never used`() {
        val localHttp = dev.owndemand.app.data.plex.dto.ResourceConnection("http", "192.0.2.10", 32400, true)
        val remoteHttp = dev.owndemand.app.data.plex.dto.ResourceConnection("http", "198.51.100.7", 32400, false)
        assertNull(ServerDiscovery.pickConnection(listOf(localHttp, remoteHttp)))
        // An http-only server is not offered at all.
        val json = """
            [ { "name": "Insecure", "provides": "server", "owned": true, "accessToken": "T",
                "clientIdentifier": "m", "connections": [
                  { "protocol": "http", "address": "192.0.2.10", "port": 32400, "local": true } ] } ]
        """.trimIndent()
        assertTrue(ServerDiscovery.serversFromResourcesJson(json).isEmpty())
    }

    @Test
    fun `uri falls back to address when absent`() {
        val plain = dev.owndemand.app.data.plex.dto.ResourceConnection("http", "10.0.0.5", 32400, true)
        assertEquals("http://10.0.0.5:32400", ServerDiscovery.connectionBaseUrl(plain))
        val withUri = plain.copy(uri = "https://10-0-0-5.t.plex.direct:32400")
        assertEquals("https://10-0-0-5.t.plex.direct:32400", ServerDiscovery.connectionBaseUrl(withUri))
    }

    @Test
    fun `connections without address are dropped`() {
        val relay = dev.owndemand.app.data.plex.dto.ResourceConnection("https", "", 0, false)
        assertNull(ServerDiscovery.pickConnection(listOf(relay)))
    }

    @Test
    fun `auth url contains encoded client id code and product`() {
        val url = PlexTvClient.buildAuthUrl("my id/123", "ab cd&ef")
        assertTrue(url.startsWith("https://app.plex.tv/auth#?"))
        assertTrue(url.contains("clientID=my+id%2F123"))
        assertTrue(url.contains("code=ab+cd%26ef"))
        assertTrue(url.contains("context%5Bdevice%5D%5Bproduct%5D=OwnDemand"))
    }
}
