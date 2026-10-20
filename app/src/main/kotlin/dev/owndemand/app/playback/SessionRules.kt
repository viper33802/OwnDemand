package dev.owndemand.app.playback

import dev.owndemand.app.data.plex.dto.ItemDto

/*
 * Rules for the controls outside the app (Chunk 4): notification, lock
 * screen, headset and picture-in-picture buttons. Pure, unit-tested.
 */

/** "Previous" this far into a video restarts it instead of going back. */
const val PREVIOUS_RESTARTS_AFTER_MS = 5_000L

/** Up next on the watch screen is the newest videos of the library, this many. */
const val UP_NEXT_SIZE = 40

/** Videos remembered for "previous" (this session only). */
const val HISTORY_LIMIT = 50

sealed interface PreviousAction {
    /** Back to the start of the current video. */
    data object Restart : PreviousAction

    /** The video played before this one. */
    data class PlayPrevious(val ratingKey: String) : PreviousAction
}

/**
 * Previous: the video played before this one in this session; more than
 * [PREVIOUS_RESTARTS_AFTER_MS] in, or with nothing before it, the current
 * video starts over (user choice 2026-10-19).
 */
fun previousAction(positionMs: Long, history: List<String>): PreviousAction =
    if (positionMs > PREVIOUS_RESTARTS_AFTER_MS || history.isEmpty()) {
        PreviousAction.Restart
    } else {
        PreviousAction.PlayPrevious(history.last())
    }

/**
 * Next: the first Up-next video (the newest [UP_NEXT_SIZE] of [library],
 * minus the current one) not finished and not already played this session,
 * so pressing next repeatedly never loops back to a skipped video.
 */
fun pickNextVideo(
    library: List<ItemDto>,
    currentKey: String?,
    played: Set<String>,
): ItemDto? = library
    .take(UP_NEXT_SIZE)
    .filter { it.ratingKey != currentKey }
    .firstOrNull { !it.isFullyWatched && it.ratingKey !in played }

/** History after leaving [ratingKey] for another video: no repeats, capped. */
fun pushHistory(history: List<String>, ratingKey: String): List<String> =
    (history.filter { it != ratingKey } + ratingKey).takeLast(HISTORY_LIMIT)
