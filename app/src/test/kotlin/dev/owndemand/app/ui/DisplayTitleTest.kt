package dev.owndemand.app.ui

import dev.owndemand.app.ui.common.displayTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTitleTest {
    @Test
    fun `strips channel prefix with dash separator`() {
        assertEquals(
            "Restoring a Rusted Hand Plane",
            displayTitle(
                "Workbench Weekly - Restoring a Rusted Hand Plane",
                "Workbench Weekly",
            ),
        )
    }

    @Test
    fun `strips en-dash and colon separators`() {
        assertEquals("B", displayTitle("Chan – B", "Chan"))
        assertEquals("B", displayTitle("Chan: B", "Chan"))
    }

    @Test
    fun `keeps title when channel does not match`() {
        assertEquals("Totally Different", displayTitle("Totally Different", "Some Channel"))
    }

    @Test
    fun `keeps title when name matches without separator`() {
        assertEquals("Channel 5 reasons", displayTitle("Channel 5 reasons", "Channel"))
    }

    @Test
    fun `keeps title when it is only the channel name`() {
        assertEquals("The Channel", displayTitle("The Channel", "The Channel"))
    }

    @Test
    fun `null or blank channel keeps title`() {
        assertEquals("Plain Title", displayTitle("Plain Title", null))
        assertEquals("Plain Title", displayTitle("Plain Title", "  "))
    }
}
