package dev.owndemand.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole tablet-detection surface, as pure functions (TABLET-PLAN §2):
 * if these boundaries hold, every screen gets the right layout mode.
 */
class LayoutModeTest {

    @Test
    fun `phone widths below 600 dp stay Phone`() {
        assertEquals(LayoutMode.Phone, widthMode(360.dp)) // Pixel-class portrait
        assertEquals(LayoutMode.Phone, widthMode(411.dp)) // Pixel 8 portrait
        assertEquals(LayoutMode.Phone, widthMode(599.dp))
    }

    @Test
    fun `Tab S6 portrait is TabletCompact`() {
        // Galaxy Tab S6 portrait: 711 dp window width.
        assertEquals(LayoutMode.TabletCompact, widthMode(600.dp))
        assertEquals(LayoutMode.TabletCompact, widthMode(711.dp))
        assertEquals(LayoutMode.TabletCompact, widthMode(839.dp))
    }

    @Test
    fun `Tab S6 landscape is TabletMedium`() {
        // Galaxy Tab S6 landscape: 1138 dp window width.
        assertEquals(LayoutMode.TabletMedium, widthMode(840.dp))
        assertEquals(LayoutMode.TabletMedium, widthMode(1138.dp))
        assertEquals(LayoutMode.TabletMedium, widthMode(1199.dp))
    }

    @Test
    fun `1200 dp and up is TabletExpanded`() {
        assertEquals(LayoutMode.TabletExpanded, widthMode(1200.dp))
        assertEquals(LayoutMode.TabletExpanded, widthMode(1600.dp))
    }

    @Test
    fun `browse columns grow one per mode`() {
        assertEquals(1, LayoutMode.Phone.browseColumns())
        assertEquals(2, LayoutMode.TabletCompact.browseColumns())
        assertEquals(3, LayoutMode.TabletMedium.browseColumns())
        assertEquals(4, LayoutMode.TabletExpanded.browseColumns())
    }

    @Test
    fun `isTablet is true only at 600 dp and above`() {
        assertEquals(false, LayoutMode.Phone.isTablet)
        assertEquals(true, LayoutMode.TabletCompact.isTablet)
        assertEquals(true, LayoutMode.TabletMedium.isTablet)
        assertEquals(true, LayoutMode.TabletExpanded.isTablet)
    }

    @Test
    fun `detail pane columns follow the pane width`() {
        // Tab S6 landscape: 1138 dp window minus the 280 dp channel rail.
        assertEquals(2, detailPaneColumns(857.dp))
        // Tab S6 portrait: 711 dp window minus the rail.
        assertEquals(1, detailPaneColumns(430.dp))
        // The full-window phone drill-in keeps the phone single column.
        assertEquals(1, detailPaneColumns(411.dp))
        // Big tablet / desktop panes grow, capped at 4.
        assertEquals(3, detailPaneColumns(1100.dp))
        assertEquals(4, detailPaneColumns(2000.dp))
    }

    @Test
    fun `side rail starts at 840 dp`() {
        assertEquals(false, LayoutMode.Phone.useSideRail)
        assertEquals(false, LayoutMode.TabletCompact.useSideRail) // portrait keeps the bottom bar
        assertEquals(true, LayoutMode.TabletMedium.useSideRail)   // Tab S6 landscape
        assertEquals(true, LayoutMode.TabletExpanded.useSideRail)
    }

    @Test
    fun `miniplayer card grows with the width class, phone unchanged`() {
        assertEquals(200.dp, LayoutMode.Phone.miniPlayerCardWidth())    // M4e size, untouched
        assertEquals(280.dp, LayoutMode.TabletCompact.miniPlayerCardWidth())
        assertEquals(320.dp, LayoutMode.TabletMedium.miniPlayerCardWidth())  // Tab S6 landscape
        assertEquals(340.dp, LayoutMode.TabletExpanded.miniPlayerCardWidth())
    }

    @Test
    fun `split-screen halves of the Tab S6 fall back to phone layout`() {
        // 1138 dp split in two is ~569 dp per app — phone layout there is
        // correct, and a narrower third (~379 dp) must stay Phone too.
        assertEquals(LayoutMode.Phone, widthMode(569.dp))
        assertEquals(LayoutMode.Phone, widthMode(379.dp))
    }
}
