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
- README refresh: the feature list and screenshots are out of date
  (no channel play, previous / next, chapters, PiP or background audio
  yet). Retake the screenshots from the mock server with simulated data
  only.

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

## Chunk 10 — Skip segments (SponsorBlock) · review first, medium

Planned 2026-10-21; not approved to build yet. It changes the "contacts
nothing but Plex" rule, so the scope change below needs sign-off first.

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

### Scope change (needs approval)

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
- **Show a skip button:** a "Skip self-promotion ›" button sits above
  the bar's right end while inside the segment.
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

Chromecast and offline downloads (large; revisit separately if wanted).
