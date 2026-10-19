# AGENTS.md — OwnDemand Project Instructions

Guidance for contributors and coding agents working in this repository.

## 1. Project Overview

**OwnDemand** is an independent, native Android application that provides a
familiar video-app-style browsing and playback experience for channel videos
stored in the user's own Plex Media Server library.

- The app only ever connects to the user's own Plex server (and plex.tv for
  sign-in and server discovery). It has no backend and no third-party
  services of its own.
- Not affiliated with or endorsed by Plex, Inc.

**Naming policy:** the source video platform and its feature brands must
never appear in code, comments, UI strings, resource names, docs, commit
messages, or any published artifact. Speak of "channel videos", "the video
library", or "the source platform".

---

## 2. Scope

### In scope
- Android phones and tablets (native Kotlin app).
- Plex Media Server integration: browsing, search, playback.
- Plex Collections presented as channels.
- Secure configuration for any user's Plex server.
- Public source code and self-hosted use.

### Out of scope (unless explicitly approved)
- iOS, tvOS, Android TV.
- Downloading from the source platform.
- Replacing or modifying Youtarr or other ingestion tools.
- A hosted backend, user accounts, or app-specific cloud services.
- A new media synchronization service.
- Short-form / vertical video presentation (see §5).

---

## 3. Responsibility Boundaries

**Youtarr** (or a similar tool) downloads and synchronizes channel content
into a Plex library. OwnDemand never duplicates that job.

**Plex** is the source of truth for media, metadata, artwork, collections,
and streaming.

**OwnDemand** browses and searches the selected Plex library, presents
channel collections, plays media, and keeps app-specific settings and
playback state.

---

## 4. Content Organization

### 4.1 Channel Collections
Each channel is a Plex Collection in the dedicated video library.

- Present collections as channels with a browsable list and a channel page.
- Use collection artwork and metadata where available.
- Collection membership is the authoritative channel relationship; a
  `"<Channel> - <Title>"` title prefix is only a display fallback.
- A video may belong to multiple collections. Do not assume exclusive
  membership.

### 4.2 Playlists — not presented
Plex playlists are treated as an ingestion mechanism, not a browsing
destination. Validation showed their video-library content is already
reachable through channel collections, so the app does not show them.

### 4.3 Library selection
- The user selects the video library from the server's libraries.
- Never silently combine unrelated libraries into the feed or search.
- Persist the library by its stable `uuid` (plus the server's
  `machineIdentifier`); resolve the numeric key at runtime and re-prompt if
  the library disappears.

See `docs/PLEX-FINDINGS.md` for the verified Plex API behaviour.

---

## 5. Product Experience

Screens: Home feed, Channels, Watch (with miniplayer), Search, Settings.
Tablet layouts are described in `docs/TABLET-PLAN.md`; player anatomy in
`docs/PLAYER-UI-SPEC.md`.

### Playback-first browsing model
- Tapping a video starts playback immediately inline — no interstitial
  details screen.
- Metadata lives in an expandable card below the player.
- Content stays browsable while a video plays; playback docks into a
  floating miniplayer.
- Channels are first-class navigation targets.
- **No short-form or vertical presentation anywhere.** All cards are 16:9
  landscape; vertical sources are letterboxed.
- Ordinary sorting must not be described as personalized recommendations.

---

## 6. Technology & Architecture

- Kotlin, Jetpack Compose, AndroidX Media3 / ExoPlayer, OkHttp, Coil,
  kotlinx.serialization, DataStore, Gradle.
- Keep clear boundaries between UI, the Plex API client and auth, library
  data access, playback, persistent configuration, and tests.
- Manual dependency container (`AppContainer`) — no DI framework.
- Avoid unnecessary modules, dependencies, abstractions, or databases. If a
  database ever becomes necessary, document its purpose and contents.

---

## 7. Security & Credentials

- **HTTPS only.** Cleartext traffic is disabled platform-wide
  (`res/xml/network_security_config.xml`); servers are reached through
  their `*.plex.direct` HTTPS connections. Never re-enable cleartext or
  follow https→http redirects.
- Never hardcode Plex credentials, tokens, server addresses, or library ids.
- Tokens are stored only as Android Keystore AES-GCM encrypted blobs and are
  excluded from backups and device transfer.
- Never log tokens or full server URLs; never show tokens in the UI,
  screenshots, example configuration, or diagnostics.
- Never transmit Plex credentials to any service other than plex.tv and the
  user's own server.
- Handle sign-out and credential removal safely.

---

## 8. Repository Hygiene

The repository must never contain:
- Plex credentials, tokens, or private server configuration.
- Personal data: server names, account names, email addresses, LAN
  addresses, device serials, home-directory paths, real channel names or
  video ids from a personal library.
- Third-party proprietary code, assets, or branding.
- Signing keys, build outputs, or logs containing secrets.

Screenshots must use simulated data only. Use `.gitignore` for local
analysis artifacts, and remember that ignoring a file does not untrack it.

---

## 9. Testing

- `./gradlew testDebugUnitTest` — JVM unit tests (parsing, server
  discovery, layout rules, token store, playback decisions).
- `./gradlew connectedDebugAndroidTest` — instrumented tests (emulator or
  device).

When reporting results, distinguish tests executed and passed, executed and
failed, not yet executed, and those that need a real Plex server or a device.
Never claim playback works from a successful compile alone, and never invent
output or API behaviour.

---

## 10. Development Workflow

- Work in explicit, bounded milestones: state the objective, inspect the
  existing code, implement only the agreed scope, run the relevant tests,
  update documentation, and report.
- Verify Plex behaviour against official documentation and real API
  responses; do not invent endpoints, parameters, or fields.
- Make coherent, reviewable commits with meaningful messages.
- Changes affecting security, licensing, architecture, data integrity, or
  scope need explicit maintainer approval.

---

## 11. Status

Feature-complete proof of concept for phones and tablets: connection and
sign-in, library selection, home feed, channels, watch/playback with
miniplayer, search, settings, captions, and scrub previews.

Next: public-release preparation (licence selection, dependency licence
review, release packaging). Google Play distribution is a possible later
phase, not a current goal.
