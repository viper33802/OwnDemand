package dev.owndemand.app.playback

import dev.owndemand.app.data.plex.dto.ChapterDto
import dev.owndemand.app.data.plex.dto.ItemDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** Chapters (Chunk 9). */
class ChaptersTest {

    private fun video(vararg chapters: ChapterDto) =
        ItemDto(ratingKey = "1", duration = 600_000, Chapter = chapters.toList())

    private fun ch(title: String, start: Long, end: Long) =
        ChapterDto(tag = title, startTimeOffset = start, endTimeOffset = end, thumb = "/library/media/1/chapterImages/1")

    @Test
    fun `chapters are sorted, zero-length ones dropped, blank titles numbered`() {
        val chapters = chaptersOf(
            video(ch("Results", 300_000, 600_000), ch("Intro", 0, 60_000), ch("", 60_000, 300_000), ch("Empty", 300_000, 300_000)),
        )
        assertEquals(listOf("Intro", "Chapter 2", "Results"), chapters.map { it.title })
    }

    @Test
    fun `one chapter or none means no chapters`() {
        assertEquals(emptyList<VideoChapter>(), chaptersOf(video(ch("Whole video", 0, 600_000))))
        assertEquals(emptyList<VideoChapter>(), chaptersOf(video()))
        assertEquals(emptyList<VideoChapter>(), chaptersOf(null))
    }

    @Test
    fun `current chapter follows the position`() {
        val chapters = chaptersOf(video(ch("A", 0, 60_000), ch("B", 60_000, 300_000), ch("C", 300_000, 600_000)))
        assertEquals(0, chapterIndexAt(chapters, 0))
        assertEquals(0, chapterIndexAt(chapters, 59_999))
        assertEquals(1, chapterIndexAt(chapters, 60_000))
        assertEquals(2, chapterIndexAt(chapters, 599_000))
        assertEquals(-1, chapterIndexAt(emptyList(), 10_000))
    }

    @Test
    fun `scrubber gaps sit at each chapter start after the first`() {
        val chapters = chaptersOf(video(ch("A", 0, 150_000), ch("B", 150_000, 450_000), ch("C", 450_000, 600_000)))
        assertEquals(listOf(0.25f, 0.75f), chapterGapFractions(chapters, 600_000))
        assertEquals(emptyList<Float>(), chapterGapFractions(chapters, 0))
    }

    @Test
    fun `description timestamps inside the video become links`() {
        val text = "0:00 Intro\n4:05 Testing\n1:02:33 Too late\nScore 10:70 and 2:3 are not times. Model 12:30."
        val found = descriptionTimestamps(text, durationMs = 20 * 60_000L)
        assertEquals(listOf(0L, 245_000L, 750_000L), found.map { it.timeMs })
        assertEquals("4:05", text.substring(found[1].range))
        // With a long video the hour-long one counts too.
        assertEquals(3_753_000L, descriptionTimestamps("1:02:33", durationMs = 2 * 3_600_000L).single().timeMs)
    }
}
