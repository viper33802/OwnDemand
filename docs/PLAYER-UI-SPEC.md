# OwnDemand Player & Feed UI Spec (M3)

## 1. Scope

Design spec for OwnDemand's playback and feed experience: a familiar
browse-while-watching video-app feel, implemented from scratch in Compose.
All dimensions and timings below are OwnDemand's own design values, tuned
on device; no third-party code, drawables, strings, or fonts are used.

## 2. Player control layout

Three zones over the video, each with a vertical scrim gradient behind it
(transparent → ~70 % black); controls fade in/out together (~150 ms).
Same anatomy inline (16:9) and fullscreen. Revised 2026-10-16: the center
transport is always shown with the controls, and the scrubber is flush with
the video's bottom edge.

**Top row** (left→right): back arrow · video title (1 line, ellipsized) ·
captions (CC). (Cast/airplay/settings: later phase.)

**Center row** (shown whenever the controls are — single tap reveals,
second tap acts): −10 s circle 48 dp · **play/pause** circle 64 dp
(icon ~34 dp, translucent circle background) · +10 s circle 48 dp.

**Bottom stack**:
1. Row: "elapsed / total" at the lower left (14 sp, medium weight, ~6 dp
   above the bar) · spacer · fullscreen toggle at the lower right, 48 dp
   target entirely inside the player bounds (T4 fix — it used to spill
   past the video edge, which became untappable on tablet landscape where
   the video ends at the nav bar).
2. Scrubber: full-width, flush with the video's bottom edge, 42 dp touch
   height; ~3 dp visible track pinned to the edge; played = accent red,
   buffered = light grey, rest = dark grey; circular thumb grows on drag;
   timestamp bubble above the thumb while dragging.

Times: `m:ss` under 1 h, `h:mm:ss` above.

**Loading**: centered spinner overlay while buffering; controls stay hidden.

## 3. Gestures & feedback

| Gesture | Action | Feedback |
| --- | --- | --- |
| Single tap video | Toggle controls | Controls fade |
| (no input) | Auto-hide controls | ~3 s after show/last input; never while scrubbing; paused = stay |
| Double-tap right half | Seek **+10 s** | `»` + running-total bubble ("+10s"), ~700 ms after the last step |
| Double-tap left half | Seek **−10 s** | mirrored `«` + "−10s" |
| **Single tap while the bubble is up** | **Tap-to-continue**: another 10 s on that side (the other side starts a new count) | Bubble shows the running total — double-tap + 3 taps = "+40s"; never toggles controls |
| ±10 s buttons (controls) | Seek ±10 s per tap | Same running-total bubble as the taps |
| Long-press anywhere (playing) | Temporary **2× speed** | Rounded chip top-center "⏩ 2x speed"; release restores 1× |
| **Downward drag from video** | **Dock to miniplayer** (playback continues) | Watch screen closes; live video appears in the mini bar |
| **Upward drag on the inline player** | **Enter fullscreen** (same as the fullscreen button) | 35 % of the player height commits; ignored in fullscreen and while zoomed |
| Horizontal drag on scrubber | Scrub | **BIF preview frame** (`/library/parts/{id}/indexes/sd/{ms}`, 5 s buckets, Coil-cached) above the thumb + timestamp; text-only bubble when the part has no `indexes` attribute |
| Double-tap center | Toggle controls (a11y parity) | — |
| **CC button** | Captions on/off; with several subtitle or audio tracks, a menu: Subtitles (Off + each language) and Audio | The chosen subtitle language carries over to the next video (Chunk 3) |
| **Pinch/spread on video** | **Realtime zoom** (1×–4×) | Video scales about the pinch midpoint; controls unaffected |
| **Two-finger drag while zoomed** | **Pan** the zoomed video | Translation clamped so the frame edges never show black |
| **One-finger drag while zoomed** | Pan (convenience) | Same clamp |
| Double-tap while zoomed | Snap back to 1× (centered) | — |

Brightness and volume vertical drags were **removed** (2026-10-16): the
app must not control device brightness or volume.

Single tap within the bottom 42 dp band scrubs (thumb drag), not toggles.

**Zoom implementation note (2026-10-15 — zoom and pan must work in real
time on the playing video):** the video
surface must be a **TextureView** (Media3 `setVideoSurfaceType`), not
SurfaceView — Compose `graphicsLayer` scale/translation transforms do not
apply to SurfaceView content. Zoom state = `scale` + `offset` in a
`graphicsLayer` on the player surface, driven by `detectTransformGestures`
(pinch centroid + pan), clamped to bounds, reset on item change and
fullscreen toggle. Zoom gestures take priority over the double-tap seek
zone only while `scale > 1`.

## 4. Inline (portrait, above-the-fold) player

Same video surface 16:9 with the **same control anatomy as fullscreen**
(revised 2026-10-16: full chrome inline — top row, center transport,
edge-flush scrubber). Tap toggles the
controls (not fullscreen); the lower-right button enters fullscreen.
Below the player: title + metadata (our Plex data: title, collection
name, duration, added date) — title 18 sp bold, meta 14 sp grey.

## 5. Feed card (home list)

Anatomy:
- Thumbnail: 16:9, full content width, duration badge bottom-right
  (black 80 % chip, white `m:ss`, 12 sp).
- Below: title 2 lines max (16 sp), then meta line: channel/collection
  name · "N views" (we have no views → use added date) (14 sp, grey).
- Single column, 8–16 dp gutters; tap = play inline; long-press = actions
  (later).

## 6. Implementation mapping (M3)

- **Surface**: Media3 `ExoPlayer` rendering to a `TextureView` (needed for
  zoom, §3) inside `AndroidView`; all chrome = Compose overlay.
- **State**: `Player.Listener` → `StateFlow` (isPlaying, position,
  buffered, duration, playbackSpeed); 250 ms ticker for position.
- **Double-tap zones**: `PointerInput` with tap-count tracking; left/right
  half by x < width/2; ripple = animated Canvas circle + label.
- **Speed chip**: `player.setPlaybackSpeed(2f)` on press, restore on
  release; ignore while paused.
- **Scrub preview**: frames from the part's BIF preview index
  (`/library/parts/{id}/indexes/sd`, parsed by `BifIndex`); timestamp-only
  bubble when the part has no index.
- **Brightness/volume**: not controlled by the app (removed, see §3).
- **Auto-hide**: coroutine job restarted on every interaction.

## 7. Still open (M3 spike, Plex-side)

1. Decision endpoint with a real `X-Plex-Client-Profile` (AV1 declared) —
   direct vs transcode answer.
2. `/:/timeline` resume/watched reporting.
3. Preview/thumbnail sprite availability for scrub preview.
4. Real AV1 direct-play on emulator (exit test).

## 8. Playback-first interaction model (2026-10-15)

The differentiator vs the Plex app: a browse-while-watching flow.
Plex buries each video clicks deep and shows details before playing;
OwnDemand plays first and keeps the app browsable.

- **Tap feed card → inline playback starts immediately** at the top of a
  watch screen. No interstitial details page.
- **Expandable description card** directly below the player: title,
  channel/collection (tappable → channel page), added date, duration,
  Plex `summary` when present. Chevron expands/collapses in place while
  the video keeps playing.
- **Up-next below**: "More from <collection/playlist>" 16:9 cards;
  tapping swaps the player item and scrolls back to the player.
- **Browse while watching**: the bottom menu stays visible while playing
  portrait (2026-10-16) — the watch screen overlays the tab
  area inside the shell; tapping any tab (or the down-drag from the player,
  or system back) hands playback to the floating **miniplayer**. The mini
  bar shows the **live video**, floats (not docked), follows a drag and
  **snaps to the nearest of the four corners**; dragging it fully off
  screen (or X) stops playback.
- **Auto-rotate** (phone behaviour): a physical turn
  to landscape → fullscreen; a turn back to portrait → inline. Collapsing
  fullscreen while still held landscape disarms the landscape trigger until
  the phone is upright again.
- **No back arrow on the player:** the menu,
  system back and the down-drag all leave playback.
- **Fullscreen** = the §2 player surface with the full control set; the
  bottom chrome lifts ~14 dp + nav-bar inset off the screen edge; the menu
  hides.
- **Titles**: Youtarr's "<Channel> - <Title>" prefix is stripped wherever
  the channel is shown separately (`displayTitle`).
- **Hard rule**: no vertical or short-form cards anywhere in the app.
  All thumbnails 16:9; vertical sources letterboxed inside 16:9.
- **Autoplay next** (Chunk 3): when a video ends on the watch screen
  (inline or fullscreen), an "Up next in 5" card with **Play now** and
  **Cancel** sits at the player's lower right; at zero the first Up-next
  video not yet finished opens (fullscreen stays fullscreen). One global
  switch in Settings, on by default.
