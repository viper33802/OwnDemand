# Android TV interface (Chunk 6)

The phone/tablet app runs on Android TV, but its screens are built for
touch: a remote cannot reach the player controls, menus or search results,
focus is hard to see, and nothing plays full screen. Android TV therefore
gets its own interface, in the same app, sharing everything below the UI:
sign-in, server connection, the in-memory library index, the playback
manager, settings and the media session.

## Detection

TV mode = the system UI mode is `UI_MODE_TYPE_TELEVISION` or the device
declares `android.software.leanback`. The same `MainActivity` then shows
`ui/tv` instead of the phone/tablet shell. The manifest adds a
`LEANBACK_LAUNCHER` entry with a 16:9 banner and marks touch and leanback as
not required, so one APK serves phones, tablets and TVs.

## Principles

- **Remote first.** Every action is reachable with the D-pad, OK and Back.
  No sliders, no pull-to-refresh, no drag gestures. OK plays; long-press
  OK opens a video's options (watched state, Continue watching, its
  channel, play from the beginning). The Menu key does the same where the
  system passes it to apps; some TV devices keep it for themselves.
- **Fixed focus.** The focused card sits at a fixed position and the row
  scrolls under it; the focused row moves to a fixed height. A strong focus
  cue: the card grows slightly and gets a solid outline.
- **10-foot sizes.** Content inside a 48 × 27 dp overscan margin, large
  type, 16:9 cards about 3½ per row.
- **Dark by default on TV.** A Theme setting (System / Light / Dark) applies
  to every device; most TVs have no system dark mode, so the TV theme is
  dark unless Light is chosen.
- **No short-form or vertical presentation** (AGENTS §5.7), no previews
  that play on focus (they cost bandwidth and could report progress).

## Screens

- **Side rail.** Search, Home, Channels, Library, Settings, with icons only;
  it expands with labels while focused. Back from any screen returns to
  Home; Back on Home leaves the app.
- **Sign-in.** Short code + `plex.tv/link` (no browser on most TVs), then
  server and library pickers as focusable lists.
- **Home.** A large header shows the focused video (artwork, title,
  channel, date, length, description start). Below it, rows: Continue
  watching, New, For you (the smart mix), one row per recently active
  channel (ending with a "View channel" tile), Recently watched.
- **Channels.** Two panes: the channel list (with new-video counts) and the
  focused channel's page — banner, counts, Play newest / Play oldest /
  Shuffle, sort, and its videos as a grid. The page follows focus without
  OK.
- **Library.** Continue watching and Recently watched, as grids.
- **Search.** An on-screen letter grid (no system keyboard needed),
  results updating as you type: matching channels, then videos.
- **Settings.** Focusable rows only; choices open a side panel
  (theme, text size, quality, skip modes). Sign-out asks first.
- **Player.** Full screen. With the controls hidden: OK plays / pauses,
  Left / Right seek 10 s, Up / Down show the controls. Controls: progress
  bar with scrub previews, previous / back 10 s / play-pause / forward
  10 s / next, subtitles, audio track, quality, speed, chapters, info.
  Skip-segment button and Undo, autoplay "Up next" countdown, buffering,
  reconnecting and error states. Back hides the controls, then leaves the
  player (playback stops and the position is saved). Home goes to
  picture-in-picture when that setting is on.

## Not in this chunk

Voice search, Cast receiver, previews on focus, recommendations rows on the
system home screen ("watch next" channel) — possible follow-ups.
