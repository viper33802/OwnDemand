package dev.owndemand.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App-level window-size classification (TABLET-PLAN §1): the layout adapts
 * to the window, never to a "is this a tablet" device flag — one rule then
 * covers the Tab S6 in either orientation, split-screen windows (narrow =
 * phone layout, correctly), and future foldables.
 *
 * Breakpoints follow the Material 3 width classes:
 *  - [Phone]          < 600 dp  — phone portrait/split; today's layouts, unchanged
 *  - [TabletCompact]  600–839 dp — Tab S6 portrait (711 dp)
 *  - [TabletMedium]   840–1199 dp — Tab S6 landscape (1138 dp)
 *  - [TabletExpanded] ≥ 1200 dp — big tablets / desktop windows
 */
enum class LayoutMode {
    Phone,
    TabletCompact,
    TabletMedium,
    TabletExpanded,
}

/** Provided by [OwnDemandAppRoot] from the live window configuration. */
val LocalLayoutMode = compositionLocalOf { LayoutMode.Phone }

/** True from 600 dp up — everything the survey called "tablet mode". */
val LayoutMode.isTablet: Boolean
    get() = this != LayoutMode.Phone

/** From 840 dp the shell swaps the bottom bar for a side rail (plan §3.1). */
val LayoutMode.useSideRail: Boolean
    get() = this == LayoutMode.TabletMedium || this == LayoutMode.TabletExpanded

/**
 * Floating miniplayer card width; the 16:9 height derives from it. Phones
 * keep the M4e 200 dp card (unchanged); at tablet widths 200 dp reads as a
 * postage stamp, so the card grows with the width class (user direction
 * 2026-10-18).
 */
fun LayoutMode.miniPlayerCardWidth(): Dp = when (this) {
    LayoutMode.Phone -> 200.dp
    LayoutMode.TabletCompact -> 280.dp
    LayoutMode.TabletMedium -> 320.dp
    LayoutMode.TabletExpanded -> 340.dp
}

/** The current window width, bucketed. */
@Composable
fun currentLayoutMode(): LayoutMode = widthMode(LocalConfiguration.current.screenWidthDp.dp)

/** Pure mapping so the breakpoints are unit-testable (TABLET-PLAN §2). */
fun widthMode(widthDp: Dp): LayoutMode = when {
    widthDp < 600.dp -> LayoutMode.Phone
    widthDp < 840.dp -> LayoutMode.TabletCompact
    widthDp < 1200.dp -> LayoutMode.TabletMedium
    else -> LayoutMode.TabletExpanded
}

/**
 * Column count for the browse grids (home feed, search results, channels,
 * channel page) — 1/2/3/4 across the four modes. At the Tab S6 sizes this
 * gives portrait 2 columns (~350 dp cards) and landscape 3 (~370 dp),
 * matching the reference app on the same panel (survey A2).
 */
fun LayoutMode.browseColumns(): Int = when (this) {
    LayoutMode.Phone -> 1
    LayoutMode.TabletCompact -> 2
    LayoutMode.TabletMedium -> 3
    LayoutMode.TabletExpanded -> 4
}

/**
 * Column count for the video grid inside the Channels detail pane
 * (T2 follow-up, user direction 2026-10-17): computed from the PANE's
 * width, not the window — the left channel rail has already taken its
 * share. Sizing target ~350 dp+ per card, so on the Tab S6 the rail
 * leaves 2 columns in landscape (≈860 dp pane) and 1 in portrait (≈430 dp).
 */
fun detailPaneColumns(paneWidth: Dp): Int {
    val gutter = 12.dp
    return ((paneWidth + gutter) / (350.dp + gutter)).toInt()
        .coerceIn(1, 4)
}

/** Continue-watching / horizontal-row card width. */
fun LayoutMode.rowCardWidth(): Dp = if (isTablet) 260.dp else 200.dp

/**
 * Caps a page to a readable/form width on big windows and centres it, so
 * settings, setup and picker pages stop stretching edge-to-edge (survey A5)
 * while phones (<640 dp) are untouched.
 */
fun Modifier.readableWidth(maxWidth: Dp = 640.dp): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
