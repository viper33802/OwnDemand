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

## Not planned

Chromecast and offline downloads (large; revisit separately if wanted).
