package dev.owndemand.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skip-segment rules on made-up fixtures (no real source ids, per the
 * plan). Ids stand in for the bracketed 11-character group Youtarr puts
 * in file names.
 */
class SkipSegmentsTest {

    // ---- id from the file name ----

    @Test
    fun `id is the last bracketed group`() {
        assertEquals("aBcDeF12345", sourceVideoId("Show - Episode [aBcDeF12345].mp4"))
        assertEquals("x1x1x1x1x1x", sourceVideoId("[2026] Title (1080p) [x1x1x1x1x1x].mkv"))
        assertEquals("zZzZzZzZzZz", sourceVideoId("Title [aBcDeF12345] [zZzZzZzZzZz].mp4"))
    }

    @Test
    fun `no id without a valid bracket group`() {
        assertNull(sourceVideoId("Title [tooshort1].mp4"))
        assertNull(sourceVideoId("Title [toolongid12345].mp4"))
        assertNull(sourceVideoId("Title [bad char!].mp4"))
        assertNull(sourceVideoId("no brackets.mp4"))
        // The last group must be real even when an earlier one would pass.
        assertNull(sourceVideoId("Title [aBcDeF12345] [toolonggroup1].mp4"))
    }

    // ---- hash prefix ----

    @Test
    fun `prefix is the first four hex digits of the sha-256`() {
        // The empty string's SHA-256 starts e3b0c442 (a well-known vector).
        assertEquals("e3b0", hashPrefix(""))
        assertEquals(4, hashPrefix("aBcDeF12345").length)
        assertTrue(hashPrefix("aBcDeF12345").all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hashPrefix("aBcDeF12345"), hashPrefix("aBcDeF12345"))
    }

    // ---- response parsing (fixtures match the live shape) ----

    private fun body(vararg entries: String) = "[" + entries.joinToString(",") + "]"

    private val entry =
        """{"videoID":"aBcDeF12345","segments":[""" +
            """{"category":"sponsor","actionType":"skip","segment":[3.2,21.4],""" +
            """"UUID":"1234567890abcdef","videoDuration":300.0,"locked":0,"votes":2,"description":""},""" +
            """{"category":"selfpromo","actionType":"skip","segment":[60.0,75.5],"videoDuration":300.0}]}"""

    @Test
    fun `only the exact video is kept from a shared prefix`() {
        val other = entry.replace("aBcDeF12345", "zZzZzZzZzZz")
        val found = segmentsFor(
            body(other, entry),
            "aBcDeF12345",
            setOf(SegmentCategory.Sponsor, SegmentCategory.SelfPromo),
        )
        assertEquals(
            listOf(
                SkipSegment(SegmentCategory.Sponsor, 3_200, 21_400),
                SkipSegment(SegmentCategory.SelfPromo, 60_000, 75_500),
            ),
            found,
        )
    }

    @Test
    fun `unwanted categories and non-skip actions are dropped`() {
        val mixed = """{"videoID":"aBcDeF12345","segments":[""" +
            """{"category":"intro","actionType":"skip","segment":[0,9]},""" +
            """{"category":"sponsor","actionType":"mute","segment":[10,20]},""" +
            """{"category":"nonsense","actionType":"skip","segment":[30,40]},""" +
            """{"category":"sponsor","segment":[50,60]}]}"""
        val found = segmentsFor(body(mixed), "aBcDeF12345", setOf(SegmentCategory.Sponsor))
        // intro not wanted, mute dropped, unknown category dropped, and a
        // segment with no actionType counts as skip.
        assertEquals(listOf(SkipSegment(SegmentCategory.Sponsor, 50_000, 60_000)), found)
    }

    @Test
    fun `garbage and empty prefix answers parse to nothing`() {
        assertTrue(segmentsFor("", "aBcDeF12345", setOf(SegmentCategory.Sponsor)).isEmpty())
        assertTrue(segmentsFor("not json", "aBcDeF12345", setOf(SegmentCategory.Sponsor)).isEmpty())
        assertTrue(segmentsFor("[]", "aBcDeF12345", setOf(SegmentCategory.Sponsor)).isEmpty())
    }

    // ---- filtering for the loaded video ----

    private val sponsor = SkipSegment(SegmentCategory.Sponsor, 3_000, 30_000)
    private val selfPromo = SkipSegment(SegmentCategory.SelfPromo, 40_000, 52_000)
    private val modes = mapOf(
        SegmentCategory.Sponsor to SkipMode.Auto,
        SegmentCategory.SelfPromo to SkipMode.Button,
        SegmentCategory.Intro to SkipMode.Off,
    )

    @Test
    fun `off categories and stubs under a second are dropped`() {
        val intro = SkipSegment(SegmentCategory.Intro, 0, 20_000)
        val stub = SkipSegment(SegmentCategory.Sponsor, 31_000, 31_900)
        val visible = visibleSegments(listOf(sponsor, selfPromo, intro, stub), modes, 300_000)
        assertEquals(listOf(sponsor, selfPromo), visible)
    }

    @Test
    fun `a segment ending at the end is left as the ending`() {
        val tail = SkipSegment(SegmentCategory.Sponsor, 292_000, 299_500) // ends 0.5 s before end
        assertTrue(visibleSegments(listOf(tail), modes, 300_000).isEmpty())
        // One that ends further from the end is kept.
        val kept = SkipSegment(SegmentCategory.Sponsor, 280_000, 295_000)
        assertEquals(listOf(kept), visibleSegments(listOf(kept), modes, 300_000))
    }

    // ---- what to do at a position ----

    @Test
    fun `every auto segment is scheduled except undone ones`() {
        // Only Auto categories. One behind the playhead stays scheduled: its
        // delivery only fires if a seek back brings playback across it.
        assertEquals(listOf(sponsor), autoSkipTargets(listOf(sponsor, selfPromo), modes, emptySet()))
        // Undo left this viewing alone with it.
        assertTrue(autoSkipTargets(listOf(sponsor), modes, setOf(sponsor)).isEmpty())
    }

    @Test
    fun `the button shows inside any enabled segment`() {
        assertNull(skipButtonFor(listOf(sponsor, selfPromo), modes, 35_000)) // between
        assertEquals(selfPromo, skipButtonFor(listOf(sponsor, selfPromo), modes, 45_000))
        // Inside an Auto segment means a seek, resume or Undo put the
        // playhead there (playback alone would have jumped it): offer it.
        assertEquals(sponsor, skipButtonFor(listOf(sponsor, selfPromo), modes, 10_000))
        // A category switched off mid-video offers nothing.
        assertNull(skipButtonFor(listOf(sponsor), modes + (SegmentCategory.Sponsor to SkipMode.Off), 10_000))
    }

    @Test
    fun `undo restores to the start of the skipped segment`() {
        val notice = SkipNotice(sponsor)
        assertEquals(sponsor.startMs, notice.restoreMs)
        assertEquals("sponsor", notice.label)
    }

    @Test
    fun `a late delivery is ignored when the playhead is nowhere near`() {
        assertFalse(staleAutoSkip(sponsor, 3_000)) // right at the start
        assertFalse(staleAutoSkip(sponsor, 4_400)) // within the 1.5 s tolerance
        assertFalse(staleAutoSkip(sponsor, 20_000)) // inside: still worth jumping out
        assertTrue(staleAutoSkip(sponsor, 1_000)) // well before the start
        assertTrue(staleAutoSkip(sponsor, 0)) // another video restarted at the top
        assertTrue(staleAutoSkip(sponsor, 30_000)) // at or past the end
    }

    @Test
    fun `defaults follow the plan`() {
        assertEquals(SkipMode.Auto, defaultSkipModes()[SegmentCategory.Sponsor])
        assertEquals(SkipMode.Button, defaultSkipModes()[SegmentCategory.SelfPromo])
        assertEquals(SkipMode.Button, defaultSkipModes()[SegmentCategory.Interaction])
        assertFalse(defaultSkipModes().containsKey(SegmentCategory.Intro))
        assertFalse(defaultSkipModes().containsKey(SegmentCategory.Outro))
        assertFalse(defaultSkipModes().containsKey(SegmentCategory.Filler))
    }

    // ---- the setting decides whether anything is ever looked up ----

    private fun videoWithId() = dev.owndemand.app.data.plex.dto.ItemDto(
        ratingKey = "1",
        duration = 600_000,
        Media = listOf(
            dev.owndemand.app.data.plex.dto.MediaDto(
                Part = listOf(
                    dev.owndemand.app.data.plex.dto.PartDto(file = "/videos/Show - Title [aBcDeF12345].mp4"),
                ),
            ),
        ),
    )

    @Test
    fun `off means no lookup happens`() {
        assertNull(segmentLookupFor(videoWithId(), SegmentSettings(enabled = false)))
        // An empty mode map would mean nothing to ask for: no lookup either.
        assertNull(
            segmentLookupFor(
                videoWithId(),
                SegmentSettings(enabled = true, modes = SegmentCategory.entries.associateWith { SkipMode.Off }),
            ),
        )
    }

    @Test
    fun `on looks up the id from the file name`() {
        assertEquals("aBcDeF12345", segmentLookupFor(videoWithId(), SegmentSettings(enabled = true)))
    }

    @Test
    fun `files without a source id are never looked up`() {
        val noId = dev.owndemand.app.data.plex.dto.ItemDto(
            ratingKey = "2",
            duration = 600_000,
            Media = listOf(
                dev.owndemand.app.data.plex.dto.MediaDto(
                    Part = listOf(dev.owndemand.app.data.plex.dto.PartDto(file = "/videos/Title.mp4")),
                ),
            ),
        )
        assertNull(segmentLookupFor(noId, SegmentSettings(enabled = true)))
    }
}
