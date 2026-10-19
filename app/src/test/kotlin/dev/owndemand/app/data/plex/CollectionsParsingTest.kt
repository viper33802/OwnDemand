package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.CollectionsResponse
import dev.owndemand.app.data.plex.dto.MediaContainerResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixtures mirror the verified live shapes (docs/PLEX-FINDINGS.md §3):
 * collections arrive under `Metadata` with composite thumbs and childCount.
 * (Playlist parsing was dropped with the Playlists tab, 2026-10-16.)
 */
class CollectionsParsingTest {

    private val collectionsJson = """
        {
          "MediaContainer": {
            "size": 2, "totalSize": 8,
            "Metadata": [
              { "ratingKey": "501", "key": "/library/collections/501/children",
                "guid": "collection://0a1b2c3d", "type": "collection",
                "title": "Orbit Notes", "subtype": "movie", "index": 1,
                "ratingCount": 1,
                "thumb": "/library/collections/501/composite/1789754286?width=400&height=600",
                "addedAt": 1788917859, "updatedAt": 1789754286,
                "childCount": 6, "maxYear": 2026, "minYear": 2026 },
              { "ratingKey": "502", "key": "/library/collections/502/children",
                "title": "Some Creator's Workshop",
                "thumb": "/library/collections/502/composite/1789754290",
                "childCount": 12, "addedAt": 1788917000 }
            ]
          }
        }
    """.trimIndent()

    // Collection children reuse the item payload, leaner than
    // /library/metadata (Media present but Stream lists empty).
    private val childrenJson = """
        {
          "MediaContainer": {
            "size": 1, "totalSize": 6,
            "Metadata": [
              { "ratingKey": "1001", "key": "/library/metadata/1001",
                "title": "Orbit Notes - Why Saturn's Rings Won't Last",
                "duration": 2031000, "addedAt": 1788917859,
                "thumb": "/library/metadata/1001/thumb/1789917866",
                "Collection": [{ "tag": "Orbit Notes" }],
                "Media": [{ "id": 1, "container": "mp4", "Part": [] }] }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `collections parse with composite thumb and child count`() {
        val resp = PlexJson.decodeFromString<CollectionsResponse>(collectionsJson)
        assertEquals(8, resp.container.totalSize)
        val first = resp.container.Metadata.first()
        assertEquals("501", first.ratingKey)
        assertEquals("Orbit Notes", first.title)
        assertEquals(6, first.childCount)
        assertEquals("/library/collections/501/children", first.key)
        assertEquals(
            "/library/collections/501/composite/1789754286?width=400&height=600",
            first.thumb,
        )
    }

    @Test
    fun `children parse through the shared item container`() {
        val resp = PlexJson.decodeFromString<MediaContainerResponse>(childrenJson)
        assertEquals(6, resp.container.totalSize)
        val item = resp.container.Metadata.single()
        assertEquals("1001", item.ratingKey)
        assertEquals(2031000L, item.duration)
        assertEquals("Orbit Notes", item.channelName)
    }
}
