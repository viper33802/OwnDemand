package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.SectionsResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionsParsingTest {

    // Synthetic fixture mirroring the real /library/sections shape.
    private val sectionsJson = """
        {
          "MediaContainer": {
            "size": 2,
            "Directory": [
              { "key": "1", "title": "Movies", "type": "movie", "uuid": "u-1",
                "agent": "tv.plex.agents.movie", "scanner": "Plex Movie" },
              { "key": "6", "title": "My Videos", "type": "movie", "uuid": "u-6",
                "agent": "tv.plex.agents.none", "scanner": "Plex Video Files" }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `sections parse with uuid key and type`() {
        val resp = PlexJson.decodeFromString<SectionsResponse>(sectionsJson)
        val dirs = resp.container.Directory
        assertEquals(2, dirs.size)
        assertEquals("u-6", dirs[1].uuid)
        assertEquals("Plex Video Files", dirs[1].scanner)
        assertEquals("6", dirs[1].key)
    }
}
