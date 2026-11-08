package dev.owndemand.app.ui.tv

/*
 * TV player rules (Chunk 6). Pure, unit-tested.
 */

/** Opens the full-screen player: a video, resumed or from its start. */
data class TvPlayRequest(val ratingKey: String, val fromStart: Boolean)

/** One Left / Right press seeks this far (a single tap = 10 s). */
const val TV_SEEK_STEP_MS = 10_000L

/** Presses closer together than this count as one held key. */
const val TV_SEEK_REPEAT_WINDOW_MS = 600L

/** The scrub position settles (the player seeks) this long after the last press. */
const val TV_SEEK_COMMIT_MS = 700L

/** Controls hide after this long without a key while playing. */
const val TV_CONTROLS_HIDE_MS = 5_000L

/** Speeds offered in the player's speed panel (5 % steps around normal). */
val TvSpeeds = dev.owndemand.app.playback.PlaybackSpeed.TV_OPTIONS

/**
 * Seek distance for the [repeat]th press in a row (0-based): 10 s for the
 * first few, then 30 s, then a minute — holding the key crosses a long
 * video quickly, a tap stays precise.
 */
fun tvSeekStepMs(repeat: Int): Long = when {
    repeat < 4 -> TV_SEEK_STEP_MS
    repeat < 12 -> 30_000L
    else -> 60_000L
}

/** [positionMs] moved by [deltaMs], kept inside the video. */
fun tvSeekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val upper = if (durationMs > 0) durationMs - 1_000 else Long.MAX_VALUE
    return (positionMs + deltaMs).coerceIn(0L, upper.coerceAtLeast(0L))
}

/** "1.25×" style speed label; 1 is "Normal". */
fun tvSpeedLabel(speed: Float): String =
    if (dev.owndemand.app.playback.PlaybackSpeed.same(speed, 1f)) "Normal"
    else "${dev.owndemand.app.playback.PlaybackSpeed.number(speed)}×"
