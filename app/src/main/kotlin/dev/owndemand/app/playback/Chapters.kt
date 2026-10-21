package dev.owndemand.app.playback

import dev.owndemand.app.data.plex.dto.ItemDto

/*
 * Chapters (Chunk 9): embedded in the video files and returned by Plex
 * with the video. Pure, unit-tested.
 */

data class VideoChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    /** Plex thumbnail path; blank when there is none. */
    val thumb: String,
)

/**
 * A video's chapters in play order. Zero-length chapters are dropped, and
 * a single chapter is no chapters at all (nothing to move between).
 */
fun chaptersOf(item: ItemDto?): List<VideoChapter> {
    val chapters = item?.Chapter.orEmpty()
        .filter { it.endTimeOffset > it.startTimeOffset }
        .sortedBy { it.startTimeOffset }
        .mapIndexed { i, c ->
            VideoChapter(
                title = c.tag.trim().ifBlank { "Chapter ${i + 1}" },
                startMs = c.startTimeOffset,
                endMs = c.endTimeOffset,
                thumb = c.thumb,
            )
        }
    return if (chapters.size < 2) emptyList() else chapters
}

/** Index of the chapter playing at [positionMs]; -1 when there are none. */
fun chapterIndexAt(chapters: List<VideoChapter>, positionMs: Long): Int {
    if (chapters.isEmpty()) return -1
    val i = chapters.indexOfLast { it.startMs <= positionMs }
    return if (i < 0) 0 else i
}

/** Where the scrubber's gaps go: each chapter start after the first, as 0–1. */
fun chapterGapFractions(chapters: List<VideoChapter>, durationMs: Long): List<Float> {
    if (durationMs <= 0) return emptyList()
    return chapters.drop(1)
        .map { it.startMs.toFloat() / durationMs }
        .filter { it > 0f && it < 1f }
}

/** A timestamp found in a description: where it sits in the text, and when. */
data class DescriptionTimestamp(val range: IntRange, val timeMs: Long)

private val TIMESTAMP = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?![\d:])""")

/**
 * Timestamps ("4:05", "1:02:33") in a description that fall inside the
 * video ([durationMs]); minutes and seconds must be real (under 60 when
 * hours are given, seconds always).
 */
fun descriptionTimestamps(text: String, durationMs: Long): List<DescriptionTimestamp> =
    TIMESTAMP.findAll(text).mapNotNull { m ->
        val hours = m.groupValues[1].toLongOrNull()
        val minutes = m.groupValues[2].toLong()
        val seconds = m.groupValues[3].toLong()
        if (seconds >= 60 || (hours != null && minutes >= 60)) return@mapNotNull null
        val ms = (((hours ?: 0) * 60 + minutes) * 60 + seconds) * 1_000
        if (durationMs > 0 && ms > durationMs) return@mapNotNull null
        DescriptionTimestamp(m.range, ms)
    }.toList()
