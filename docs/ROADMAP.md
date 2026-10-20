# OwnDemand Roadmap

Status: agreed 2026-10-19. Each chunk is planned in detail and approved
before work starts, tested on the emulator (Android TV: on a Shield TV),
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

Built 2026-10-19 (decisions from the user):
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
  lagged visibly in PiP (checked on the Tab S6) and gapped the audio.
- The emulator's GPU crashed repeatedly on PiP and its decoder sometimes
  stalled when the video surface changed while a stream started; the app
  now re-seeks in place when stuck "buffering" with data loaded. Check
  PiP on a real device.

## Chunk 5 — Polish and distribution · small

- Accessibility pass: screen-reader labels and touch targets on the custom
  player controls.
- Opt-in "new version available" notice from GitHub Releases — off by
  default, so the app still contacts nothing but Plex unless enabled.

## Chunk 6 — Android TV · review first, then large

1. **Spike:** install the current app on a Shield TV and record what breaks
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

Planned and built 2026-10-19 (user decisions in brackets). Ships as
0.3.1, a point release.

- **Where:** a **Play** button in the channel page header, next to the
  sort button (phone page and tablet two-pane alike). It opens three
  choices: **Oldest first**, **Newest first**, **Shuffle** [one order,
  no separate "unwatched only" switch]. Nothing is added to the Channels
  list itself: the channel page is one tap away.
- **What plays:** every video in the channel, in that order. Order uses
  the video's original upload date when Plex has it, else the date it was
  added. Part-watched videos resume where you left off, watched ones start
  from the beginning [resume as normal]. Shuffle is a fresh random order
  each time.
- **Play queue:** while a channel plays, it replaces Up next for
  everything that moves to the next video: autoplay in the app (the
  5-second countdown), autoplay outside it, and the next button on the
  notification, lock screen, headset and PiP window. Previous keeps
  working from this session's history.
- **Watch screen:** Up next shows the rest of the queue in order, under
  a "Playing *Channel* · oldest first" line with an × to stop playing the
  channel (Up next goes back to normal).
- **End:** after the last video the queue stops [once through, no loop].
  Tapping any other video outside the queue also ends it.
- **Tests:** unit tests for the orders (upload date, added-date
  fallback), shuffle containing every video exactly once, and next / end
  of queue. Emulator: each order, autoplay through two videos, next from
  the notification, end of queue, leaving the queue.

### Also in this PR: previous / next in the player (planned and built 2026-10-19)

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

## Not planned

Chromecast and offline downloads (large; revisit separately if wanted).
