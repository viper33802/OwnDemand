package dev.owndemand.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TrackOptionsTest {
    private val TEXT = 3  // C.TRACK_TYPE_TEXT
    private val AUDIO = 1 // C.TRACK_TYPE_AUDIO

    private fun t(g: Int, type: Int, label: String?, lang: String?, selected: Boolean = false, supported: Boolean = true) =
        TrackInfo(g, 0, type, label, lang, supported, selected)

    @Test
    fun `subtitle options are labelled by label, then language, then number`() {
        val options = trackOptions(
            listOf(
                t(0, TEXT, "English", "en", selected = true),
                t(1, TEXT, null, "fr"),
                t(2, TEXT, null, null),
                t(3, AUDIO, null, "en"),
                t(4, TEXT, "Unsupported", "de", supported = false),
            ),
            TEXT,
        )
        assertEquals(3, options.size)
        assertEquals("English", options[0].label)
        assertEquals(true, options[0].selected)
        assertEquals(Locale.forLanguageTag("fr").getDisplayLanguage(Locale.getDefault()), options[1].label)
        assertEquals("Track 3", options[2].label)
    }

    @Test
    fun `audio options only list audio tracks`() {
        val options = trackOptions(listOf(t(0, AUDIO, null, "en"), t(1, AUDIO, null, "de"), t(2, TEXT, null, "en")), AUDIO)
        assertEquals(2, options.size)
    }
}
