package dev.owndemand.app.ui

import dev.owndemand.app.playback.SkipMode
import dev.owndemand.app.ui.settings.skipModeShortLabel
import dev.owndemand.app.ui.settings.textSizeLabel
import dev.owndemand.app.ui.setup.libraryKindLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class PolishLabelsTest {
    @Test
    fun `text size label names the size in plain words`() {
        assertEquals("Standard", textSizeLabel(1f))
        assertEquals("Standard", textSizeLabel(1.001f)) // slider float noise
        assertEquals("Smaller · 85 %", textSizeLabel(0.85f))
        assertEquals("Larger · 130 %", textSizeLabel(1.3f))
        assertEquals("Larger · 150 %", textSizeLabel(1.5f))
    }

    @Test
    fun `library kinds read as words, not API values`() {
        assertEquals("Movies and videos", libraryKindLabel("movie"))
        assertEquals("TV shows", libraryKindLabel("show"))
        assertEquals("Music", libraryKindLabel("artist"))
        assertEquals("Photos", libraryKindLabel("photo"))
        assertEquals("Other", libraryKindLabel("other"))
    }

    @Test
    fun `skip mode rows use words short enough never to split`() {
        // Each word must fit half a phone row at the largest text size.
        SkipMode.entries.forEach { mode ->
            skipModeShortLabel(mode).split(' ').forEach { word -> assert(word.length <= 11) { word } }
        }
        assertEquals("Auto-skip", skipModeShortLabel(SkipMode.Auto))
    }
}
