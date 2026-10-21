# OwnDemand Tablet Mode — Implementation Plan

Date: 2026-10-17
Status: Complete (T0–T5 shipped). Originally proposed for approval (per AGENTS.md §10, no
implementation before approval).
Evidence: `TABLET-SURVEY.md` + `docs/tablet-survey/` (archived to the local
git-ignored `archive/` on 2026-10-18 — kept for reference, no longer shipped).
Target tablet: 2560×1600 @360 dpi (sw711dp; 1138×711 dp landscape),
Android 12 / API 32. Emulator: `owndemand_api36` AVD re-spec'd to the same
metrics.

## 1. Principles

1. **Detect the window, not the device.** Tablet mode triggers from the
   current window's size class (Material 3 width classes), not
   `smallestScreenWidth` device flags. One rule then covers: target tablet
   landscape (1138 dp → Expanded), portrait (711 dp → Medium), split-screen
   / desktop-mode windows (a narrow window gets the phone layout — correct), and
   future foldables. Phone behaviour (<600 dp) must not change at all —
   the phone is still the reference device.
2. **Recompose, don't restart.** `MainActivity` already handles
   `orientation|screenSize|screenLayout|smallestScreenSize` itself, so a
   rotate resizes the layout live with the player running. Every adaptive
   switch below must stay pure-Compose layout so playback survives rotation
   mid-video.
3. **No short-form anything** (AGENTS.md §5): grid cards stay 16:9
   landscape thumbnails with text underneath; no vertical/reels cards.

Width breakpoints (Material 3 standard):

| Window width dp | Name here | target tablet case |
| --- | --- | --- |
| < 600 | `Phone` | split-screen slivers |
| 600 – 839 | `TabletPortrait` class **Medium** | portrait (711) |
| 840 – 1199 | `TabletLandscape` class **Expanded** | landscape (1138) |
| ≥ 1200 | `TabletXL` (future: Tab S9 Ultra, desktop mode) | — |

"Tablet mode" = width ≥ 600 dp.

## 2. Foundation (touches everything, do first)

- Add `androidx.compose.material3:material3-window-size-class`
  (minSdk-safe, Compose BOM already aligned).
- In `MainActivity`, `calculateWindowSizeClass(this)` + the live
  configuration; provide a small app-level value:

  ```kotlin
  enum class LayoutMode { Phone, TabletCompact, TabletMedium, TabletExpanded }
  val LocalLayoutMode = staticCompositionLocalOf { LayoutMode.Phone }
  fun widthMode(widthDp: Dp): LayoutMode = when {
      widthDp < 600.dp -> LayoutMode.Phone
      widthDp < 840.dp -> LayoutMode.TabletCompact   // target tablet portrait
      widthDp < 1200.dp -> LayoutMode.TabletMedium   // target tablet landscape
      else -> LayoutMode.TabletExpanded
  }
  ```

- Column-count helper used by every grid:
  `browseColumns = 1 / 2 / 3 / 4` for the four modes.
- Form/readable-content cap helper: `Modifier.readableWidth()` →
  `widthIn(max = 640.dp)` centred, for settings/setup/list-form screens.
- JVM unit test for `widthMode`/`browseColumns` — the whole detection
  surface, no instrumentation needed.

## 3. Screen-by-screen design

### 3.1 Shell (`MainShell.kt`)
- **TabletMedium/Expanded (≥840)**: replace `NavigationBar` with
  `NavigationRail` (Home · Channels · Settings; search icon top of rail —
  Home's wordmark row keeps its own search button on smaller modes).
  Content inset = rail width; miniplayer docks over content, above nav.
- **TabletCompact (600–839)**: keep the bottom `NavigationBar` (thumb reach
  in portrait) but centre it in a width-capped container (~560 dp) instead
  of edge-to-edge stretching.
- Watch screen stays a state inside the shell (AGENTS.md §5 unchanged);
  in fullscreen the rail/nav already hide (`fullscreenNow` branch).

### 3.2 Home (`HomeScreen.kt`)
- Feed: `LazyColumn` → `LazyVerticalGrid(GridFixedColumns(browseColumns))`,
  content padding 12 dp, gap 12 dp. `FeedCard` itself unchanged — at
  350–370 dp cells it returns to phone-proportional look, thumbnails stop
  being upscaled (fixes A2 + blur).
- Continue watching: keep `LazyRow`; card width 200 dp → **260 dp** in
  tablet modes; rows already scale gracefully.
- Continue-watching/segment/mix logic in `HomeViewModel` untouched —
  presentation only.
- Sort menu, wordmark row: unchanged (L14 showed the menu behaves).

### 3.3 Search (`SearchScreen.kt`)
- Results use the same `browseColumns` grid as Home.
- Text field + empty state capped via `readableWidth()`, centred.
- Search logic untouched.

### 3.4 Channels tab (`ChannelsScreen.kt`) — revised 2026-10-17
The T2 grid-of-rows version was rejected on the device: **"I don't like how
the list of channels is being displayed on tablet. What about a left column
for the channel names."** Shipped instead at ≥600 dp: **two-pane
master–detail** — a 280 dp left rail listing channels (subscription-style row,
selection highlight, pagination preserved, first channel auto-selected)
beside the selected channel's videos. The detail pane computes its own
column count from its width (`detailPaneColumns`, target ≥350 dp/card):
Target tablet landscape → **2 columns**, portrait → **1 column** — exactly the
brief. Phones keep the drill-in list → full-screen channel page.

### 3.5 Channel page (same file)
- Pinned header: stays full width (avatar · name · count · sort); the
  back arrow only renders in the phone full-screen variant — in the
  two-pane the rail is the way back.
- Video list: `detailPaneColumns` grid, pane-width aware (see §3.4).
- Sort menu behaviour unchanged.

### 3.6 Watch (`WatchScreen.kt`) — the critical one
Three layout regimes (fixes A1):

| Regime | Layout |
| --- | --- |
| Phone (<600) | current stacked layout, unchanged |
| 600–839 (portrait) | current stacked layout — already works (survey P03); only add: player capped at `min(width*9/16, 45% of height)` with letterbox so the card header always peeks, and B2-sized controls scale for the wider player |
| ≥840 (landscape) | **two-pane**: `Row { left(weight 0.6): sticky player 16:9 + expandable card; right(weight 0.4, min 360 dp): Up-next list, own scroll }`. Keeps every element on screen at 1138×711. |

- **Fullscreen-button hit-area fix (all modes)**: give the bottom-row
  controls a ≥48 dp touch target that sits inside the player's own bounds
  (not clipped by the nav strip); verified by re-running the survey tap.
- Miniplayer: dock offset must avoid the NavigationRail in landscape mode.
- BIF scrub previews: attach to the pane player width, not the window
  (check `BifIndex` overlay sizing during implementation).
- Fullscreen playback: unchanged behaviour; on a tablet the letterbox
  centring is already correct at 2560×1600.

### 3.7 Settings + Setup + Library picker
- Wrap content in `readableWidth()` centred column (640 dp): sliders get a
  sane max width (480 dp for the text-size slider), buttons stop
  full-bleed, switch-server / connect forms become normal dialogs-width.

### 3.8 Theming bug B1 (fold into any milestone)
- Wordmark: a dark-surface (white) and a light-surface (brand-colour)
  variant selected by `isSystemInDarkTheme()`. (Resolved originally with the
  pre-rebrand mark; the OwnDemand wordmark ships both cuts natively.)

## 4. Non-goals for this phase

- No new screens, no Android TV, no picture-in-picture, no multi-select,
  no drag-and-drop customization.
- No per-device hacks (desktop-mode / split-screen "optimisations"): the
  width-class rule already handles them.
- No changes to Plex data layer, playback pipeline, or `MainViewModel`
  state machine.
- Playlists stay removed (AGENTS.md §4.2).

## 5. Milestones

| # | Deliverable | Screens | Est. |
| --- | --- | --- | --- |
| **T0** | Done: tablet-spec'd AVD + baseline survey + plan | — | ✔ |
| **T1** | **Done 2026-10-17.** Foundation: `LocalLayoutMode` from the live window Configuration (no extra dependency — the plan sketched `material3-window-size-class`, but `LocalConfiguration.screenWidthDp` + the pure `widthMode()` mapping give identical behaviour with one less artifact, and it is what `LayoutModeTest` pins), `browseColumns`, `rowCardWidth`, `readableWidth`; `readableWidth` on Settings/Setup/LibraryPicker + 480 dp slider cap; width-capped centred nav bar; B1 wordmark fixed via `BrandWordmark` + `brand_wordmark_light.png` (white→dark "Plex" redraw; both themes verified). After-shots: `tablet-survey/after-T1/`; tests: 7 new, all green | shell + forms | small |
| **T2** | **Done 2026-10-17.** Grids: Home feed + search results `browseColumns` (3 landscape / 2 portrait / 1 phone, zero-gutter at 1 column so phones are pixel-identical); search field capped + centred; continue-watching 260 dp. **Channels revised mid-milestone → two-pane master–detail (§3.4): 280 dp channel rail + detail pane with own-width columns (2 landscape / 1 portrait), selection highlight, first-channel auto-select, pagination kept; phone drill-in untouched.** After-shots: `tablet-survey/after-T2/` (B1–B10); 8 unit tests green incl. `detailPaneColumns` | browse surfaces | medium |
| **T3** | **Done 2026-10-17.** `NavigationRail` for ≥840 dp (search icon, Home, Channels; Settings pinned at the foot), bottom bar kept centred for 600–839 portrait, unchanged for phones; fullscreen hides both. The feared nav-rail + channel-rail double stack works — rail 80 dp, channel rail 280 dp, 2 video columns — so no per-tab exception was needed. Content extraction (`ShellContent`) keeps one code path for both shells; miniplayer docks inside the content area, never under the rail. After-shots `tablet-survey/after-T3/` (C1–C5); +1 test (`useSideRail` boundary), 9 green | shell landscape | small–medium |
| **T4** | **Done 2026-10-17.** Two-pane inline watch at rail width (player + card left 60 %, Up-next scrolls right 40 %) — survey A1 fixed; expanded card, Up-next tap, BIF previews verified in-pane. Portrait tablets keep the stacked watch; the planned 45 % player cap proved unnecessary (711 dp width → 400 dp tall = 35 %, card header always visible). Phone-only orientation rules gated: tablets never portrait-lock inline, never rotate-to-fullscreen, fullscreen keeps the held orientation (SCREEN_ORIENTATION_USER); the upright-sensor auto-collapse stays phone-only — it false-fires on a tablet lying flat (emulator collapsed fullscreen 100 ms after opening — found and fixed with a temporary log). Fullscreen button: 48 dp target fully inside the player (was an 11 px clipped sliver over the nav bar). After-shots tablet-survey/after-T4/ (D1–D8) | watch | medium–large |
| **T5** | **Done 2026-10-18.** Full sweep on the tablet emulator: home / channels two-pane / watch / fullscreen / mini / settings × light+dark × both rotations (`docs/tablet-survey/after-T5/`, local — not committed). Phone regression run by temporarily `wm size/density`-overriding the tablet AVD to 1080×2400@420 (411 dp) instead of a fresh AVD — same connected app, width-driven Phone mode incl. its rules: single column, bottom bar, stacked watch, rotate-to-fullscreen fires via the accelerometer (`emu sensor`), upright re-arms it, BACK restores inline. Restored to 2560×1600@360 after. Two app-side false alarms, both proven emulator artifacts (uiautomator node tree + re-composite showed complete correct layout): a black band on the portrait home (stale gfxstream blit region after rotation) and the mis-oriented fullscreen surface under the `wm` override. Installed on a real tablet at the end of T5 for a hands-on check. | all | medium |

Each milestone ends with an emulator screenshot pass in
`docs/tablet-survey/after-<milestone>/` (local only — the folder is
git-ignored since the PNGs show account and server names).

## 6. Acceptance criteria (the target tablet's metrics)

1. Landscape 1138×711: home shows **3 columns**; channels grid ≥3; watch
   shows player **and** Up-next simultaneously; metadata card openable;
   fullscreen toggle tappable; no dead zones from the nav strip.
2. Portrait 711×1138: **2 columns** on browse surfaces; stacked watch
   keeps P03 behaviour with the card header always visible.
3. Phone <600 dp: pixel-identical behaviour to today (regression check on
   the recreated phone AVD).
4. Rotating mid-playback keeps audio/video continuous and lands in the
   right layout.
5. `./gradlew test` green incl. new `widthMode`/column tests.
6. No change to: feed mix logic, search logic, playback decisions, settings
   semantics.

## 7. Risks / notes

- **Grid scroll state**: switching columns on rotate resets position —
  acceptable (common in video apps), noted not silently changed.
- **material3-window-size-class** adds one dependency; Google-maintained,
  matches existing M3 usage; no API surface beyond size values.
- **Split-screen / desktop windowing** (try on a device): narrow windows fall to
  Phone layout by design.
- **Target tablet is API 32** — CI/emulator target is 36; anything newer
  than API 32 APIs already guarded by minSdk 26 rules; window-size-class
  needs nothing new.
- Repo hygiene: survey PNGs reference private account/server names, so
  `docs/tablet-survey/` (and `archive/`) are git-ignored; after-shots stay
  local. Nothing survey-related is published (settled 2026-10-18).
- Phone regression (done in T5): instead of a fresh AVD — which would
  have needed a whole Plex sign-in — the tablet AVD was temporarily
  `wm size`/`wm density` overridden to the original phone metrics. Caveat
  found: `user-rotation lock` semantics flip under the override (the
  override redefines the natural orientation), and the override makes the
  emulator's compositor paint some fullscreen rotations wrong — verify
  phone rotation rules through the `adb emu sensor` accelerometer path,
  not the software rotation lock.
- Emulator GPU note: after a `wm`-override experiment the emulator can
  crash or hang on GPU init (`-gpu auto`); relaunch with
  `-gpu swiftshader_indirect`, and delete the crash-consent db
  (`/tmp/android-$USER/emu-crash-*.db`) if startup stalls on
  "Showing crashdialog to get consent". `wm size/density` overrides
  survive reboots — `wm size reset` if the AVD boots phone-sized.
