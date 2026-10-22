# OwnDemand Roadmap

Status: agreed 2026-10-19. Each chunk is planned in detail and approved
before work starts, tested on the emulator (Android TV: on an Android TV device),
and shipped as one PR and release.

Suggested order: **1 → 2 → 3**, then the **Chunk 6 spike**, then
**4, 7, the full TV build, 5**. The TV spike can run at any point.

## Chunk 1 — Works everywhere (reliability) · small–medium

- **Away-from-home playback.** The app picks one server address at setup
  (home network first) and keeps it, so off the home network it cannot
  reach the server. Keep all of the server's HTTPS addresses, try home
  first and fall back to remote, re-check on network changes and failures.
- **Auto-refresh on return.** Reload the library quietly when the app comes
  back to the foreground after a while (new Youtarr downloads appear
  without a manual pull).
- **CI on GitHub.** Build and run unit tests on every PR and push to `main`.

## Chunk 2 — Library control · medium

- Mark watched / unwatched (long-press a card).
- Hide a video from Continue watching.
- "New" badges on channels and videos added since the last visit
  (one last-visit timestamp kept on the device).
- Channel banners on channel pages (backdrop art already uploaded by
  `tools/plex-collection-art.sh`).

Builds on the single-video update path from 0.2.0.

## Chunk 3 — Playback options · medium

- Autoplay the next video (countdown, on/off setting).
- Quality choice: Original / 1080p / 720p / Data saver.
- Subtitle and audio track selection (check the library's tracks first).

## Chunk 4 — Picture-in-picture and background audio · large

- Floating picture-in-picture window; audio continues with the app in the
  background or the screen locked.
- Lock-screen / notification playback controls (media session).

Separate chunk: it changes how Android runs the player (a background
service), so it needs its own testing.

Behaviour (built 2026-10-19):
- PiP and background audio both on by default, each with a Settings switch.
  With background audio off, leaving the app pauses (PiP still plays).
- Notification / lock screen / PiP / headset buttons: previous · play-pause
  · next. Next = the first unfinished Up-next video not yet played this
  session. Previous = the video before this one this session; more than
  5 s in (or none before it) restarts the current one. ±10 s stays in the
  in-app player only.
- Outside the app, autoplay starts the next video at once (no countdown).
- Artwork, title and channel on the lock screen; the artwork goes through
  an app-private URI so no server address or token is in media metadata.
- Media notifications need no notification permission (Android exempts
  them), so the app asks for none.
- PiP draws on a SurfaceView. Closing the PiP window pauses; swiping the
  app away from Recents stops playback.
- A drop to 480p in PiP / background was tried and removed: the switch
  lagged visibly in PiP (checked on a tablet) and gapped the audio.
- The emulator's GPU crashed repeatedly on PiP and its decoder sometimes
  stalled when the video surface changed while a stream started; the app
  now re-seeks in place when stuck "buffering" with data loaded. Check
  PiP on a real device.

## Chunk 5 — Polish and distribution · small

- Accessibility pass: screen-reader labels and touch targets on the custom
  player controls.
- Opt-in "new version available" notice from GitHub Releases — off by
  default, so the app still contacts nothing but Plex unless enabled.
- ~~README refresh~~ — done for 0.4.1: features regrouped (Browse,
  Watch, Your server) and brought up to date; new phone and tablet
  screenshots of a fictional demo library served by a throwaway mock
  server (HTTPS, trusted by a temporary debug-only override that is never
  committed).
- Large text sizes clip player labels (seen on a tablet at a raised
  text size): the "Skipped sponsor · Undo" chip cuts "Undo" off (a fixed
  40 dp button box, and no gap after the label), and the quality label in
  the player's top bar shows "Aut" instead of "Auto". Let both grow with
  the text size.

### Visual polish pass (review 2026-10-21)

Reviewed every screen on the emulator in phone (1080×2340 @420), tablet
portrait and tablet landscape, dark and light, at standard text and at the
largest in-app text size (×1.5). Findings and the fix for each:

**Launch**
1. Every launch flashes the sign-in screen, then a near-empty Home for
   about a second. The root starts from a placeholder "signed out" state
   until saved settings load. Fix: a branded splash (app icon on the app
   background, wordmark below on Android 12+) held until settings are
   known and the library's first load is in (capped at 2.5 s); skeleton
   cards on Home if it is still loading after that.

**Text size**
2. Text in fixed-size icon buttons clips: quality "Aut", Undo chip
   "Und". Fix: pill text buttons that grow with their text.
3. Menus, bottom sheets and dialogs ignore the in-app text size (sort,
   play, speed, quality, captions and skip-mode menus; the long-press and
   chapter sheets; the licences and confirm dialogs): they open in a new
   window that resets the scale. Fix: re-apply the app's scale inside them.
4. The Text size slider slides away while it is dragged (the page above it
   reflows live), and says "Larger text everywhere" even when set smaller.
   Fix: apply on release with a live preview line; label Smaller /
   Standard / Larger with a percentage.
5. The "N new" pill squeezes channel names (the tablet rail cuts them to
   a word or two at large sizes). Fix: the pill moves to the subtitle
   line, after the video count.
6. Rail and bottom-bar labels have no single-line rule; at large sizes
   "Channels" already fills the rail. Fix: one line, never wrapping.

**Layout**
7. On phones the Undo chip sits on top of the play controls. Fix: bottom
   left, just above the bar (the skip button keeps the bottom right).
8. Tablet portrait: the bottom bar is a 560 dp slab with gaps either
   side. Fix: full-width bar, items still centred.
9. Tablet landscape: the side rail stops below the status bar, leaving a
   dark notch above it (clearest on the watch screen). Fix: the rail's
   colour runs the full height.
10. In fullscreen the fullscreen button still shows "enter fullscreen".
    Fix: an exit-fullscreen icon there.
11. Back with the tablet chapter panel open leaves the video instead of
    closing the panel. Fix: Back closes the panel first.
12. The library picker labels libraries "type: movie". Fix: a plain kind
    ("Movies and videos", "Music", …).

Checked and fine: Settings, Search, Library, channel pages, the watch
screen and fullscreen at both text sizes (long titles ellipsize), the
light theme, the miniplayer. The miniplayer went black once after a theme
switch on the emulator (its known decoder stalls); check on a device.

**Built (2026-10-21):** all twelve. Notes on how:
- Splash: Android 12+ uses the system splash (`values-v31` themes: app
  background, icon, wordmark as the branding image); older versions show
  `splash_window` as the window background. `MainActivity` holds the first
  frame until `AppContainer.launchReady`, which the root sets once settings
  are read and the library's first load is in (2.5 s cap; 4 s hard cap in
  the activity). `AppSettings.loaded` marks the placeholder settings, so
  the root never decides "signed out" before reading them.
- Popups: `ui/common/AppTextScale.kt` — `LocalAppDensity`,
  `AppTextScale { }` and `AppDropdownMenu` (use it for every new menu).
- Rail / bottom-bar labels grow with the text size up to ×1.3, then stay.
- Emulator re-check: launch (splash straight to Home), tablet landscape
  at ×1.5 (rail, channel rows, menus, player row, Undo chip, chapter
  Back, fullscreen icon, slider drag), tablet portrait (full-width bar)
  and phone.
- Device check (release build): an Android 12 tablet (system splash) at
  about ×1.7 overall text, and an Android 11 phone (window-background
  splash) at standard and ×1.7. Both launch from the splash straight to a
  loaded Home; menus, sheets, the player's top row, the Undo chip and the
  skip button are whole at every size; the slider responds to taps and
  drags.
13. Found on the phone at ×1.7: in the Skip segments rows the mode text
    took the whole width and broke category names mid-word. Now name and
    mode share the row, and the row shows short mode words ("Auto-skip",
    "Skip button", "Off"); the menu keeps the full wording.

Left as is: on a phone's small inline player with the controls up, the
Undo chip's top edge touches the "previous" button's circle (both stay
readable and tappable).

## Chunk 6 — Android TV · review first, then large

1. **Spike:** install the current app on an Android TV device and record what breaks
   (launch, D-pad navigation, playback).
2. **Decide the approach** — likely the same app with a separate TV
   interface (Compose for TV) sharing sign-in, library and player.
3. **Build:** TV home rows, channel browser, full-screen player with remote
   controls, PIN sign-in (already approved on a phone/computer).

Requires updating the AGENTS.md scope (Android TV is currently out of scope).

## Chunk 7 — On-device recommendations · review first, medium

Computed on the device from the library's own watch history (view counts,
last-viewed dates); no data leaves the device, no external service.
Candidate rows: more from the channels watched most, channels drifted away
from, videos of a typical length. Each row is labelled for what it is
(e.g. "From channels you watch"), never presented as personalised AI
recommendations. Starts with a review of what the data supports; requires
updating the AGENTS.md rule on recommendations.

## Chunk 8 — Play a channel · small–medium

Built 2026-10-19; shipped in 0.3.1.

- **Where:** a **Play** button in the channel page header, next to the
  sort button (phone page and tablet two-pane alike). It opens three
  choices: **Oldest first**, **Newest first**, **Shuffle** (one order,
  no separate "unwatched only" switch). Nothing is added to the Channels
  list itself: the channel page is one tap away.
- **What plays:** every video in the channel, in that order. Order uses
  the video's original upload date when Plex has it, else the date it was
  added. Part-watched videos resume where you left off, watched ones start
  from the beginning. Shuffle is a fresh random order
  each time.
- **Play queue:** while a channel plays, it replaces Up next for
  everything that moves to the next video: autoplay in the app (the
  5-second countdown), autoplay outside it, and the next button on the
  notification, lock screen, headset and PiP window. Previous keeps
  working from this session's history.
- **Watch screen:** Up next shows the rest of the queue in order, under
  a "Playing *Channel* · oldest first" line with an × to stop playing the
  channel (Up next goes back to normal).
- **End:** after the last video the queue stops (once through, no loop).
  Tapping any other video outside the queue also ends it.
- **Tests:** unit tests for the orders (upload date, added-date
  fallback), shuffle containing every video exactly once, and next / end
  of queue. Emulator: each order, autoplay through two videos, next from
  the notification, end of queue, leaving the queue.

### Also in this release: previous / next in the player

- **Buttons:** the player's centre row (shown when you tap the video,
  inline and fullscreen) becomes **previous · play/pause · next** instead
  of −10 s · play/pause · +10 s. They do exactly what the notification's
  buttons do, and use our own drawn icons.
- **±10 s stays as a gesture:** double-tap the left or right side, with
  the running total bubble ("+40s"), as now.
- **Greyed out when there's nothing to do:**
  - *Next* is greyed when nothing follows: the channel's last video, or
    no unfinished Up-next video left.
  - *Previous* is greyed only on the first video of the session in its
    first 5 s (no earlier video, nothing to restart); otherwise it goes
    back a video, or restarts after 5 s.
- **Notification / lock screen / headset:** unchanged (always offer
  previous and next; at the end of a channel next does nothing).
- **Tests:** unit tests for when each button is available. Emulator:
  inline and fullscreen, phone and tablet layouts, greyed states at the
  start of a session and at the end of a channel.

## Chunk 9 — Chapters · medium

Built 2026-10-20.

**Source.** Chapters embedded in the video files, which Plex returns with
the video (`/library/metadata/{id}?includeChapters=1`): a title, start and
end time, and a Plex-made thumbnail per chapter
(`/library/media/{id}/chapterImages/{n}`). Zero-length chapters are
skipped, and a single chapter counts as none. Descriptions are not parsed
for chapters (their timestamps become links instead, below). Videos
without chapters look and work exactly as before.

**In the player (the familiar pattern):**
- **Segmented scrubber.** The progress bar splits into segments with a
  small gap at each chapter start.
- **Current chapter by the time.** Bottom left reads
  "4:12 / 20:32 · *Chapter title* ›". It updates as the video plays;
  tapping it opens the chapter list.
- **While scrubbing,** the chapter title shows above the preview frame.

**Chapter list.** A panel listing every chapter with its thumbnail,
title and start time; the current one is highlighted and tapping one
jumps there. It opens from the chapter title in the player and from a
"Chapters" row on the description card. On phones and in fullscreen it
slides up from the bottom; on the tablet's two-pane screen it takes the
place of Up next, with an × to go back.

**Description.** Timestamps in a description ("12:34") become tappable
and jump to that point.

**Unchanged:** previous / next stay whole-video (not chapter) buttons;
double-tap ±10 s stays.

**Not in this chunk:** Plex can also mark where the end credits start
(`includeMarkers=1`, `credits`). That could start the autoplay countdown at
the credits instead of the very end — a separate idea to decide later.

**Also in this release: upload dates.** When Plex has a video's upload
date (`originallyAvailableAt`), cards and the line
under the title show it instead of the date added, and the channel page's
Newest / Oldest sort uses it (like Play a channel). Home's sort and the
New badges stay on the date added (new *to the library*).

**Tests:** unit tests for reading chapters (zero-length skipped, sorted,
current chapter at a position, scrubber gap positions) and for finding
timestamps in descriptions. Emulator: a video with many chapters and one
without; scrubbing across chapters; the list in phone, fullscreen and
tablet layouts; tapping a description timestamp.

## Chunk 10 — Skip segments (SponsorBlock) · shipped in 0.4.0

Planned 2026-10-21; the owner approved the scope change (the one opt-in
exception to "contacts nothing but Plex") the same day and the POC is
committed on `main` behind the off-by-default setting.

**POC status (2026-10-21):** steps 1–2 done — live API check against a
real library (100 of 150 sampled videos had segments; response shape
verified: timings come as `segment:[start,end]`), built with 15 unit
tests, emulator walkthrough (confirm dialog, per-category pickers, auto
skip + Undo chip + scrubber marks) and a release-signed install on a
real tablet.

**Finished (2026-10-21):** SponsorBlock credited with its own attribution
template ("Uses SponsorBlock data licensed used under CC BY-NC-SA 4.0 from
https://sponsor.ajay.app/") next to the setting, and in a "Third-party
data" section of `NOTICE` (so also under Settings → Open-source licences),
with the licence link and the non-commercial constraint. README (intro,
Features, Security & privacy, Licence) and AGENTS.md §1/§14 describe the
opt-in exception. On the tablet (release build): auto skip in the app,
in picture-in-picture, and in the background with the screen off all
jumped the sponsor and kept playing; a video without chapters works the
same (checked on the emulator). Shipped in 0.4.0.

**Review fixes (2026-10-21):** the skip button now shows whenever the
playhead is inside an enabled segment, not only while the controls are up
(they hide after 3 s); seeking or resuming into an Auto segment offers the
button, as planned; an Auto segment is skipped again after a seek back
(only Undo leaves it alone); settings changed mid-video apply at once (the
lookup asks for every category so no second request is needed); the Undo
chip times out in the player, so a skip in the background never offers a
stale Undo later; the setting text no longer says "nothing is ever sent".
Emulator: auto skip, re-skip after a seek back, Undo, the button with
controls hidden, and mid-video setting changes all checked. On the
emulator a seek to one segment end left the goldfish H.264 decoder stuck
"buffering" (the one-shot re-seek did not clear it; a seek elsewhere did).
The same skip on a real tablet (hardware H.264, release build) jumped and
kept playing, so it is an emulator decoder fault. Clipped Undo chip at
large text sizes: see Chunk 5.

### Finding each video

- **The source id comes from the file name.** Youtarr names files with
  the source video's 11-character id in brackets (`Title [id].ext`).
  Plex's metadata for these libraries carries no id (`guid` is
  `tv.plex.agents.none`), so the app reads the last bracketed
  11-character group of `Media/Part/file`, which it already receives. No
  id, no lookup.
- **Looked up when a video plays,** not marked at download time: segments
  are added and voted on for days after a video comes out, so a
  download-time snapshot is often incomplete.

### Scope change (approved 2026-10-21)

AGENTS.md and the README promise the app talks to nothing but Plex. This
feature adds one opt-in exception, **off by default**: the SponsorBlock
API over HTTPS, read-only (no submitting or voting, no user id). The
wording in AGENTS.md §1, the README's Security & privacy section and the
setting itself would say exactly what is sent.

### Privacy: the server never learns which video

- The app hashes the video id (SHA-256) and asks only for the first 4 hex
  characters: `/api/skipSegments/{prefix}`. The service returns segments
  for every video sharing that prefix, and the app keeps only its own.
- The request uses its own HTTP client: no Plex token, no Plex headers,
  no server address, no device or account details.
- Endpoint, parameters and response shape are checked against the live
  API in step 1 before anything is built (AGENTS.md: no invented
  endpoints).

### Licence

SponsorBlock's segment data is CC BY-NC-SA 4.0: the app credits it
(setting text and Settings → Open-source licences, and NOTICE as a data
source), and it must stay non-commercial — fine for free GitHub releases,
a constraint on any future paid distribution.

### Settings (Playback section)

- **Skip segments** — master switch, off. Its description names
  SponsorBlock and says what is sent.
- **Per category:** *Skip automatically*, *Show a skip button* or *Off*.
  Defaults: Sponsor → skip automatically; Self-promotion and Interaction
  reminder → show a button; Intro / intermission, Endcards / credits,
  Preview / recap, Filler / tangent, Non-music → off.

### In the player

- **On the scrubber:** segments of enabled categories drawn in a
  category colour over the chapter segments.
- **Skip automatically:** when playback reaches a segment's start it
  jumps to the end, and a "Skipped sponsor · Undo" chip shows for 5 s
  (Undo jumps back and leaves that segment alone for this viewing).
- **Show a skip button:** a "Skip self-promotion ›" button sits at the
  player's bottom right (above the bar while the controls are up) while
  inside the segment, controls up or not.
- **Seeking into a segment never auto-skips** (you chose to be there);
  the skip button appears instead.
- **Edges:** segments under 1 s are ignored; one ending within 1 s of the
  end counts as the end (so it finishes, marks watched and autoplays as
  usual).
- **Everywhere playback runs:** background audio and picture-in-picture
  skip the same way (it's the player, not the screen). Watch progress stays
  in real video time.

### How it's built

- `data/segments/SegmentClient.kt`: the separate HTTPS client, a 3 s
  timeout, and one request per video start, in parallel with the stream.
  Any failure means no segments, silently. The result is kept in memory
  for the session, with no disk cache.
- `playback/SkipSegments.kt` (pure, unit-tested):
  - the id from the file name (the last bracketed 11-character group);
  - the hash prefix;
  - parsing the response;
  - filtering by category and length;
  - and what to do at a position (entering vs. seeking in, Undo, the edges).
- `PlaybackManager` holds the current video's segments. It schedules a
  player message at each enabled segment start, so there's no polling loop.

### Steps

1. **Check (with sign-off, since it contacts the service):** look up
   a sample of videos by hash prefix and report how many have segments
   and which categories. That shows whether the feature is worth it, and
   confirms the API shape.
2. Build behind the setting (off), with unit tests for id extraction,
   prefix, parsing and skip decisions (made-up fixtures, no real ids).
3. Emulator: a video with a sponsor segment (auto-skip and Undo), a
   button category, seeking into a segment, background and PiP, the
   setting off (no request is made: check the network log).

### Branch or not

Not a long-lived branch. The player code changes with nearly every chunk
(chapters, scrubber, previous / next), so a branch would drift and need
painful merges, and the feature is small and self-contained. It lives in
`main` behind a setting that is off by default, which keeps the "nothing
but Plex" promise for anyone who doesn't turn it on. If a build that
*cannot* contact third parties is ever wanted (e.g. for an F-Droid-style
listing), that's a build flavour in `main`, not a branch.

## Not planned

- **Casting:** held (2026-10-21). The app stays free of closed-source
  libraries, which rules out Google's Cast SDK. To revisit once Chunk 6
  (Android TV) works: the phone app could hand playback to OwnDemand
  running on the TV. A sender written against the open Cast protocol was
  the other option considered.
- **Offline downloads** (large; revisit separately if wanted).
