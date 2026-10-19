# OwnDemand M1 — Live Plex API Findings

Date: 2026-10-15. Read-only discovery scan of a development Plex Media Server,
focused on the video library. **No writes were performed** (GET/HEAD only).

- Server: PMS **1.43.4.10903-e5521bd8c**, Linux, claimed, `apiVersion 1.2.3`
  (matches the official OpenAPI spec used in the discovery report).
- Transport tested: plain HTTP on the LAN.
- Auth: owner `X-Plex-Token` (supplied out-of-band; never logged or committed).

**Privacy:** this document is sanitized — the account email, full
`machineIdentifier`, LAN host, and absolute media file paths are redacted or
replaced with placeholders. Raw response bodies (which contain those values)
live only in `analysis/plex-scan/` (gitignored — verified). Do not copy raw
values into this file or any tracked file.

---

## 1. Library layout — CONFIRMED

`GET /library/sections` returns the server's libraries. The target
(section key `6` in the examples below — your key will differ; persist the
`uuid`, not the key):

| Field | Value |
|---|---|
| key | `6` (numeric, **unstable** — do not persist alone) |
| title | `<video-library display name>` |
| type | **`movie`** |
| agent | `tv.plex.agents.none` (local assets, no online agent) |
| scanner | **`Plex Video Files`** |
| uuid | stable per-library identifier (persist this, not `key`) |

Other sections (Movies, TV Shows, Music, Audiobooks) exist and **must be
excluded** from the OwnDemand feed/search.

**Library selection persistence (resolves discovery-report §4.3):** persist
`machineIdentifier` + section **`uuid`**; store `title` for display; resolve
the live numeric `key` at runtime by matching uuid. Re-prompt if the uuid
disappears (library removed/renamed).

## 2. Video items — CONFIRMED

`GET /library/sections/6/all?sort=addedAt:desc` (paginated). Total item count
(via `X-Plex-Container-Size: 0` header → `totalSize`). Each item:

| Field | Example / note |
|---|---|
| `ratingKey` | e.g. `12345` (server-local id) |
| `guid` | `tv.plex.agents.none://12345` |
| `type` | `movie` |
| `title` | **`"<Channel> - <Video title>"`** — channel name is embedded in the title with a ` - ` separator |
| `duration` | milliseconds (int) |
| `addedAt` / `updatedAt` | epoch seconds |
| `year`, `originallyAvailableAt` | present |
| `summary` | present (may be long) |
| `thumb` | `/library/metadata/{ratingKey}/thumb/{ts}` → verified `200 image/jpeg` |
| `Collection[]` | `[{ "tag": "<Channel Name>" }]` — **the channel is a collection tag on the item** |
| `Genre[]`, `Image[]`, `UltraBlurColors` | present (extra artwork/blur colors available) |

**Channel identity:** two consistent sources — (a) the `Collection` tag on
each item, and (b) the `<Channel> - ` title prefix. The collection tag is the
authoritative grouping (matches the user's statement that Youtarr channels
become Plex Collections). The title prefix is a usable display fallback.

## 3. Channels = Plex Collections — CONFIRMED (resolves the §4.5 conflict)

`GET /library/sections/6/collections` returns one collection per
channel), each:

| Field | Value |
|---|---|
| `ratingKey` | collection id |
| `key` | `/library/collections/{id}/children` (server-returned key) |
| `guid` | `collection://{uuid}` |
| `type` / `subtype` | `collection` / `movie` |
| `title` | channel name (e.g. `Orbit Notes`, `Some Creator’s Workshop`) |
| `childCount` | number of videos in the channel |
| `thumb` | `/library/collections/{id}/composite/{ts}?width=400&height=600` → verified `200 image/jpeg` (2:3 composite) |
| `minYear`/`maxYear`, `addedAt` | present |

Members: `GET /library/collections/{id}/children` (the returned key) —
**verified paged** (`X-Plex-Container-Start/Size` honored; `totalSize`
reported). Both `/children` and `/items` resolve
(200); **use the server-returned `key` verbatim** rather than hardcoding.

> The AGENTS.md §4.1 model (channels = Collections) is **correct for this
> server**. The Youtarr "folder-per-channel" default is how files are laid on
> disk; the Plex library surfaces them as Collections. No competing
> synchronization is needed — read collection membership from Plex.

## 4. Playlists — CONFIRMED, then REMOVED from the app (2026-10-16)

`GET /playlists?playlistType=video` returns the video playlists (Youtarr
names them `YT: <name>`). Each has `key=/playlists/{id}/items`. Members:
`GET /playlists/{id}/items` — **verified** returns items in order, each
with full `Media`.

**Redundancy validation (2026-10-16, user direction):** every video in
every playlist was checked against channel-collection membership:

- The Youtarr `YT:` playlists — every video already in a channel collection;
  one of them was empty.
- One unrelated playlist — **not in the video library at all**: its items
  were `episode` items in the **TV Shows** library. Surfacing them in OwnDemand was cross-library leakage.

Conclusion: playlists are a Youtarr ingestion mechanism; the Playlists
tab and its data-layer methods were removed (AGENTS.md §4.2). One
library item had no Collection tag — a Youtarr tagging gap worth fixing server-side;
it still appears in the home feed, just not under a channel.

## 5. Search — IMPORTANT CONSTRAINT

- **Global `/hubs/search?query=…&sectionID=6` does NOT scope to the section.**
  Despite `sectionID=6`, it returned hubs for episodes, shows, artists,
  tracks, actors, etc. — i.e. **cross-library results**. Using it would
  violate AGENTS.md §4.3 (never mix unrelated libraries into search).
- **Re-verified at M5 (2026-10-16):** `/library/search?query=guitar` returns
  `SearchResult[].Metadata` from **all** libraries (Music/Movies/TV mixed
  in), and `scope=6` does **not** narrow it. `/library/sections/6/search`
  → 400 Bad Request. Server-side section-scoped free-text search is not
  available on this server.
- **Correct scoped search = section media query.**
  `GET /library/sections/6/all?title=<query>` — **verified** returns only
  channel `movie` items. Because the channel name is embedded in the title,
  a title query also matches channel names.
- Tag-field search (`collection=<name>`) uses exact tag match, not substring
  (`collection=Orbit` → 0 hits when the tag is `Orbit Notes`). Prefer title
  search for free-text; use exact collection names for channel drill-down.
- Autocomplete available at `/library/sections/6/autocomplete?title.query=…`
  (not exercised; documented in spec).

**Decision (M5, final):** OwnDemand search is **local**. The whole library
(hundreds of items; pages of 100 fetch in ~80 ms on the LAN) is loaded once into
`LibraryIndex` and filtered in memory by title + channel name — instant,
fully section-scoped, and the same index powers sorting, the watched
filter, Continue watching and the mixed home feed. Server search endpoints
are not used.

## 6. Sorting, filters, resume — CONFIRMED AVAILABLE

`GET /library/sections/6?includeDetails=1` advertises pivots and fields:

- Pivots: `all`, `unwatched`, `newest`, `recentlyAdded`, `recentlyViewed`,
  `onDeck`, `collection`, plus movie-style pivots (genre/year/etc.).
- Sortable/filterable fields include: `title`, `addedAt`, `originallyAvailableAt`,
  `year`, `duration`, `viewCount`, `lastViewedAt`, `unwatched` (bool),
  `inProgress` (bool), `collection` (tag), `resolution`, codecs.
- **Home feed ordering:** `sort=addedAt:desc` (recently added) — verified.
- **Watched filter:** `unwatched=1` accepted (200). Note: `limit=0` does not
  return `totalSize`; use the `X-Plex-Container-Size: 0` header for counts.
- **Resume / Continue Watching:** `GET /library/sections/6/onDeck` returns
  items with **`viewOffset`** (ms) — verified. `viewCount` present on items. Resume is feasible from item
  metadata; progress reporting uses the timeline feature key `/:/timeline`
  (write path — not exercised in this read-only scan).

## 7. Playback — CRITICAL FINDING: AV1-dominant library

Stream probe over 30 items (oldest + newest):

| Aspect | Distribution |
|---|---|
| **Video codec** | **av1: 26, h264: 3, vp9: 1** (AV1 ≈ 87%) |
| Resolution | mostly 1920×1080, some 1280×720 |
| Audio codec | **aac** (all) |
| Subtitles | **srt** (embedded, ~half of items) |
| Container | **mp4** (all) |

Item `Media`/`Part` structure (verified):
- `Media.container = mp4`, `Part.key = /library/parts/{partId}/{ts}/file.mp4`.
- `Part.file` = absolute server path (redacted here; contains the channel
  folder and source-video id, e.g. `…/<Channel Name>/… [<video-id>].mp4`).

### 7a. Direct play — WORKS at the HTTP layer
`GET /library/parts/{id}/{ts}/file.mp4` with the token (header **or**
`?X-Plex-Token=` query param) returns **`206 Partial Content`**,
`Accept-Ranges: bytes`, `Content-Type: video/mp4`. This is exactly what
ExoPlayer needs to direct-play. **Query-param token auth works**, so ExoPlayer
and Coil can load media/artwork without custom header plumbing.

### 7b. Server transcode decision — Generic profile refuses AV1
`GET /video/:/transcode/universal/decision?…` requires a client profile:
- No profile / unknown product → **400 Bad Request**.
- `X-Plex-Client-Profile-Name: Generic` → 200, but:
  - `directPlayDecisionCode 3000` — "No direct play video profile exists for
    protocol http, container mp4, video codec **av1**."
  - `transcodeDecisionCode 4005` — "No conversion profile found for protocol
    **hls**."
  - `generalDecisionCode 2000` — "Neither direct play nor conversion is
    available."
- `X-Plex-Client-Profile-Extra` augmentations (`add-direct-play-profile` for
  av1, `add-transcode-target` for hls/h264) sent alongside Generic did **not**
  flip the decision (still 3000/4005). The Generic profile's built-in
  limitations aren't overridden by these augmentations in the tested form.

### 7c. Playback design consequences (grounded)
1. **Primary path = direct play.** Point ExoPlayer at the `Part.key` URL with
   the token; rely on the **Android device's AV1 decoder** (Media3/ExoPlayer
   uses the platform AV1 decoder; hardware AV1 is common on 2021+ phones).
   h264/vp9 items direct-play trivially.
2. **The server's decision endpoint is not usable with a synthetic/Generic
   profile for AV1.** The real app must ship a proper Plex client profile
   (full XML via `X-Plex-Client-Profile-Extra`, matching what the official
   Plex Android app sends) so the server reports correct direct-play/stream
   decisions. **Resolve the exact profile in M3 with a real client** — this
   scan proves the need but not the final profile string.
3. **Emulator risk (elevated).** Android emulator images may lack hardware
   AV1; software AV1 decode works but is slow and may stutter at 1080p. This
   is now the **top playback risk** for the emulator-based test target the
   user chose. Mitigations to evaluate in M3: use an AV1-capable system image,
   test on a physical AV1 device, or implement a server-side transcode path
   (requires solving the client-profile decision in 7b).
   *Update (M0):* the dev machine is Apple Silicon (fast native arm64
   emulation) and a physical Android device is available. Risk downgraded
   to MEDIUM; confirm empirically in M3.

### 7d. Transcoding constraint (design decision, 2026-10-15)
**The app must not depend on external pre-transcoding tools** (e.g. Tdarr)
— other users may not have them, and such a dependency would hurt
shareability.
**Design rule:** the in-app fallback for un-decodable media must use the
**PMS built-in transcoder** (`/video/:/transcode/universal/decision` →
`start.m3u8` HLS), which exists on every Plex server. Pre-transcoding
remains an optional server-side optimization, never a requirement.

## 8. Artwork — CONFIRMED
- Item thumb and collection composite thumb both return `200 image/jpeg`
  (header and query-param auth).
- `photo/:/transcode?url=…&width=…` returned **400** with the tested params —
  server-side resize needs correct encoding; **non-blocking** because direct
  thumb URLs work and Coil can downscale client-side. Revisit only if
  bandwidth becomes a concern.

## 9. Impact on the plan

| Discovery-report item | Status after M1 |
|---|---|
| §4.5 channel-representation conflict | **Resolved** — channels are Plex Collections. |
| Library selection persistence | **Resolved** — persist section `uuid` + machineIdentifier. |
| Search scoping | **Resolved** — use section media query, not `/hubs/search`. |
| Home feed ordering | **Resolved** — `sort=addedAt:desc`. |
| Resume | **Resolved (read side)** — `viewOffset` present; write side (`/:/timeline`) to implement in M3. |
| Playback | **Refined + new risk** — AV1-dominant; direct-play works; server decision needs a real client profile; **emulator AV1 is the top risk**. |

### Revised risk ranking
1. AV1 on the emulator — **MEDIUM** (downgraded after M0: Apple Silicon
   host; physical device also available). Confirm empirically in M3.
2. Client-profile decision string for correct server decisions — MEDIUM (M3).
3. Everything previously "unknown" about library/collection/playlist/search
   shape — **now resolved**.

## 10. Next step
M1 exit criteria met. **M0 completed 2026-10-15** (see below). Next: M2
(app skeleton + connection/auth/library picker) on user approval.

## 11. M0 environment — INSTALLED & VERIFIED (2026-10-15)

| Component | Version | Location |
|---|---|---|
| JDK (persisted `JAVA_HOME` in `~/.zshrc`) | openjdk 21.0.12.1 | `/opt/homebrew/opt/openjdk@21` |
| Android SDK (`ANDROID_HOME`) | cmdline-tools 22.0, licenses accepted | `/opt/homebrew/share/android-commandlinetools` |
| Platform | android-36 (API 36 / Android 16) | `platforms/android-36` |
| Build-tools | 36.1.0 | `build-tools/36.1.0` |
| platform-tools (adb) | 37.0.1 | `platform-tools/` |
| Emulator | 37.2.12 | `emulator/` |
| System image | android-36 google_apis arm64-v8a (rev 7) | `system-images/…` |
| AVD | `owndemand_api36` (Pixel 8 profile) | `~/.android/avd/` |

**Boot verification:** headless boot (`-gpu host`) reached
`sys.boot_completed=1` in ~15 s; Android 16, arm64-v8a.

**AV1 empirical result (resolves the top playback risk):** the image ships
software AV1 decoders `c2.android.av1.decoder` **and
`c2.android.av1-dav1d.decoder`** (dav1d). Reference performance for dav1d at
1920×1080 is 17–54 fps (generic CI figures); on an Apple Silicon host (native
arm64 execution) 1080p30 software AV1 is expected to be comfortable.
**Final confirmation happens in M3 against real Plex AV1 content**; the
user's physical Android device remains the fallback for hardware AV1.

## 12. M2 — App skeleton + connection — DONE (2026-10-15)

**Toolchain pinned (supersedes the compileSdk-36 plan):** the current
AndroidX/OkHttp artifacts require compileSdk 37 + AGP ≥ 9.1, so the project
uses **AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21 (built-in Kotlin — AGP 9
rejects the standalone `org.jetbrains.kotlin.android` plugin), Compose BOM
2026.09.00, compileSdk 37, targetSdk 36, minSdk 26, JDK 21**. Platform
`android-37.0` + build-tools 37.0.0 installed. OkHttp 5.5.0,
kotlinx-serialization 1.11.0, DataStore 1.2.1.

**Server connection — the plex.direct discovery (IMPORTANT):**
- `clients.plex.tv/api/v2/resources` for this account offers **HTTPS only**
  (no http connection entries at all).
- Each connection carries a `uri` like
  `https://192-0-2-10.<serverToken>.plex.direct:32400` — the dashed IP
  prefixed to a per-server token domain. This is the ONLY hostname the
  server's TLS certificate (Let's Encrypt, CN `*.<serverToken>.plex.direct`)
  matches, and Plex DNS resolves it to the right address from anywhere
  (LAN name → LAN IP, public name → public IP). Verified: strict
  `curl https://192-0-2-10.<serverToken>.plex.direct:32400/identity` → 200.
- The `<serverToken>` is NOT the machineIdentifier (they differ on this
  server). Never derive cert hostnames from machineIdentifier.
- **Rule for the app: always use the connection `uri` verbatim as the base
  URL** (fallback `protocol://address:port` only if `uri` is absent).
  Connecting to the raw LAN IP over HTTPS always fails hostname
  verification; plain HTTP to the IP works but is not what /resources
  advertises.

**Token storage bug (fixed):** `TokenStore.decrypt` initialized the GCM
cipher but never called `doFinal` — it returned ciphertext bytes decoded as
UTF-8. Sign-in appeared to work because the flow used the in-memory PIN
token; any restart then sent garbage as `X-Plex-Token`. Fixed with a real
`doFinal` + injectable key provider + 5 JVM round-trip/tamper tests.

**Emulator quirk:** this AVD boots with empty `net.dns1` and Java DNS fails
("Unable to resolve host") even when `ping` resolves. Launch with
`-dns-server 8.8.8.8,1.1.1.1`. Sign-in hardening: PIN poll timeout 10 min,
transient poll errors tolerated (5 consecutive = give up), sanitized
logcat diagnostics under tag `PlexSetup`.

**M2 verified on emulator `owndemand_api36`:** PIN sign-in (user-approved) →
token persisted (Keystore AES-GCM) → server discovery → auto-select the dev server
via plex.direct HTTPS → `/library/sections` listed 5 libraries → the video library
selected (uuid-persisted) → main shell → **cold restart lands directly in
the shell**. 12 unit tests green. No tokens in logs.

## 13. M3 spike — playback decisions (2026-10-15)

**`X-Plex-Client-Profile` is IGNORED for direct-play decisions on PMS
1.43.4** (negative test: a profile with empty `DirectPlayProfiles` still
returned "Direct play OK" for an h264 item; the server uses its built-in
profile for the product). The header is still REQUIRED (400 without it).
Built-in verdicts: h264/aac/mp4 → direct play OK; av1 → refused
(`directPlayDecisionCode 3000`) but `transcodeDecisionCode 1001`
"Conversion OK".

**Consequence — client-side playback decision (the design):**
1. App decides from the item's own streams + known device capability:
   mp4/mkv + (av1|h264) + aac → **direct play** the Part key URL
   (206/range verified in M1). AV1 decodes on our targets (dav1d).
2. Else **HLS transcode fallback**, verified end-to-end on the AV1 item:
   `GET /video/:/transcode/universal/start.m3u8?path=…&protocol=hls`
   → master playlist → `session/<id>/base/index.m3u8` (1 s segments)
   → `session/<id>/base/NNNNN.ts` (3.2 MB fetched) →
   `stop?session=<id>` (200). Segments 404 until the transcoder has
   produced them — ExoPlayer's HLS loader retries naturally.
   The decision endpoint is NOT needed for this flow.
 3. **Transcode endpoints require the X-Plex identification headers**
    (Product/Version/Client-Identifier/Device/Platform/Platform-Version)
    on the request — token alone returns **400** (verified: same URL 400
    → 200 once headers added). Media3's `DefaultHttpDataSource` must send
    them explicitly; the app shares them via `PlexHeaders.identification()`.

**Timeline:** `POST /:/timeline?ratingKey=…&key=…&state=playing&time=…
&duration=…` → 200. Resume + watched state via timeline reporting.
Note: timeline POSTs update `viewOffset` (resume verified) but do NOT make
the client appear in `GET /sessions` — official apps also register presence
via `/:/presence`. Cosmetic (server dashboard only); add a presence
heartbeat later if wanted.

**Scrub previews:** the sprite-JSON endpoint
`/library/metadata/{id}/previews` → 404 on this PMS, but that was the
WRONG probe (earlier editions of this doc wrongly concluded "no
previews"). This server ships **BIF preview indexes** on parts:
`Part@indexes="sd"` + `GET /library/parts/{partId}/indexes/sd/{timeMs}`
→ JPEG frame (verified 200 on the dev server). OwnDemand buckets timeMs to 5 s
and loads frames with Coil in the scrubber (M4f). Lesson: one 404 on
one endpoint is not "feature unavailable" — check the docs first.

## 14. M4a — collections & playlists endpoints (2026-10-16)

Verified against the live server while building the Channels/Playlists tabs:

- `GET /library/sections/6/collections` (paged via `X-Plex-Container-*`
  headers) → the channel collections under `Metadata`: `ratingKey`, `key`
  (`/library/collections/{id}/children`), `title`, `thumb`
  (`/library/collections/{id}/composite/<ts>?width=400&height=600`, 2:3,
  200 image/jpeg **with the token as a query param**), `childCount`.
  Server order = alphabetical by title.
- `GET /library/collections/{id}/children` (paged) → items with
  title/duration/thumb/Collection tag, `Media` present but **`Stream`
  lists empty** — playback still re-fetches `/library/metadata/{key}`
  (full streams) before deciding direct vs transcode, so this is display
  data only.
- `GET /playlists?playlistType=video` → playlists under `Metadata`:
  `ratingKey`, `key` (`/playlists/{id}/items`), `title`, `summary`,
  `composite` (artwork, **no query params** on this server), `leafCount`,
  `duration` (total ms).
- `GET /playlists/{id}/items` (paged) → items **in playlist order**.
- **Artwork URL gotcha:** collection composite paths already carry
  `?width=…&height=…`; appending `?X-Plex-Token=` produced a double-`?`
  URL and blank posters. Separator must vary (`&` when the path has `?`).
  Fixed in `PlaybackDecision.artworkUrl` with a regression test.

## 15. M6 — subtitles, transcode caps, BIF file format (2026-10-16)

- **Subtitles:** every item carries an English SRT sidecar — a
  `Stream@streamType="3"` whose `/library/streams/{id}` path serves plain
  `text/plain` SRT. Media3 renders it via
  `MediaItem.SubtitleConfiguration` (`APPLICATION_SUBRIP`); enabling the
  track *type* alone does not select a non-default track — also call
  `setPreferredTextLanguage("en")`.
- **Transcode quality caps:** `maxVideoBitrate=1200&videoQuality=480p&
  audioBitrate=128` on `/video/:/transcode/universal/start.m3u8` are
  honoured (probe: 1080p source → 718×404@895k segment). The request must
  carry the standard `X-Plex-*` identification headers or PMS ignores the
  session. `/transcode/sessions` (with those headers) lists live sessions —
  used to verify the data-saver toggle server-side.
- **BIF file format** (`GET /library/parts/{id}/indexes/sd`, verified
  byte-by-byte against the dev server): 72-byte header — magic `0x46494289`
  ("\x89BIF"), version marker, stream type, frame count (u32 @12), JPEG
  stream start (u32 @68). Then `frame count` entries of
  `(u32 timestamp-seconds, u32 absolute file offset)`; the FIRST entry
  describes the SECOND frame (frame 0 starts at the header's stream start)
  and the LAST entry is a `0xFFFFFFFF` sentinel whose offset is the file
  length. Frames between consecutive offsets are plain JPEGs (320×180 on
  this server, one every ~2 s). Whole file ≈ 1–3 MB per 25-min video →
  OwnDemand fetches it once at playback start and serves scrub previews
  from RAM (`BifIndex`).
- **Coverage gap:** a handful of items — all recent adds — have **no**
  `Part@indexes` attribute and `/indexes/sd` → 404: PMS has not generated
  preview indexes for them yet. App behaviour: timestamp-only scrub preview.
  Server-side fix: the library agent's "Generate video preview media"
  setting (user action).

## 16. Chunk 3: quality caps, transcode sessions (2026-10-19)

- **Caps:** `videoResolution=1280x720&maxVideoBitrate=4000` on
  `/video/:/transcode/universal/start.m3u8` is honoured: a 1080p h264
  source came back as a **1280x720, ~2.5 Mbps** HLS video track (logged
  from the player's selected track format). A 1080p source under the
  1080p cap direct-plays (no conversion). The 1080p conversion parameters
  (`videoResolution=1920x1080&maxVideoBitrate=8000`) only apply to taller
  sources; none were available to test.
- **Sessions:** each playback start sends its own `session=<uuid>`; when
  the player switches away, `/video/:/transcode/universal/stop?session=`
  ends the server-side conversion.
- **Pitfall:** right after switching from a direct-played 1080p file to a
  720p conversion, Media3's `onVideoSizeChanged` can still report
  1920x1080 (decoder reuse). Judge delivered quality by the selected
  track's `Format`, not the decoder output size.
