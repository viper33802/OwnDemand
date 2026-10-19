package dev.owndemand.app.ui.player

import kotlin.math.abs

/** One 10 s seek step (double-tap halves, tap-to-continue, ±10 buttons). */
const val SEEK_STEP_MS = 10_000L

/** How long the seek bubble stays up after the last step; taps inside it continue the burst. */
const val SEEK_BURST_WINDOW_MS = 700L

/**
 * A run of same-direction seek steps shown as one bubble ("+40s").
 * [lastAtMs] is the uptime of the latest step; the burst is live for
 * [SEEK_BURST_WINDOW_MS] after it.
 */
data class SeekBurst(val forward: Boolean, val totalMs: Long, val lastAtMs: Long) {
    fun isLive(nowMs: Long): Boolean = nowMs - lastAtMs <= SEEK_BURST_WINDOW_MS

    /** Bubble text: "+40s" / "−40s". */
    val label: String get() = (if (forward) "+" else "−") + "${totalMs / 1000}s"
}

/**
 * Adds one step to [previous] when it is still live and goes the same
 * way; otherwise starts a new burst (first step, or a change of direction).
 */
fun nextSeekBurst(previous: SeekBurst?, forward: Boolean, nowMs: Long): SeekBurst =
    if (previous != null && previous.forward == forward && previous.isLive(nowMs)) {
        previous.copy(totalMs = previous.totalMs + SEEK_STEP_MS, lastAtMs = nowMs)
    } else {
        SeekBurst(forward = forward, totalMs = SEEK_STEP_MS, lastAtMs = nowMs)
    }

/** What a single-finger drag on the player means once it passes the touch slop. */
enum class PlayerDrag {
    /** Not decided yet: still inside the slop. */
    Undecided,
    /** Downward: dock the video to the miniplayer. */
    Dock,
    /** Upward on the inline player: enter fullscreen. */
    Fullscreen,
    /** Sideways (scrubber territory) or upward in fullscreen: not ours. */
    Ignore,
}

private const val DRAG_SLOP_PX = 18f

/** Classifies a drag by its offset from the touch-down point. */
fun classifyPlayerDrag(dx: Float, dy: Float, fullscreen: Boolean): PlayerDrag = when {
    dy > DRAG_SLOP_PX && dy >= abs(dx) -> PlayerDrag.Dock
    dy < -DRAG_SLOP_PX && -dy >= abs(dx) ->
        if (fullscreen) PlayerDrag.Ignore else PlayerDrag.Fullscreen
    abs(dx) > DRAG_SLOP_PX -> PlayerDrag.Ignore
    else -> PlayerDrag.Undecided
}

/** A drag commits (dock / fullscreen) once it covers this share of the player height. */
const val DRAG_COMMIT_FRACTION = 0.35f
