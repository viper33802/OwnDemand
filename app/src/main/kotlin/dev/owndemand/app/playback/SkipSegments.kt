package dev.owndemand.app.playback

import dev.owndemand.app.data.plex.dto.ItemDto
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Skip segments (Chunk 10): SponsorBlock timings for the source video a
 * file came from, matched by its id in the file name. Everything here is
 * pure (the network call lives in data/segments/SegmentClient) and
 * unit-tested with made-up fixtures.
 */

/** What a hidden stretch of the video is. Names follow the service. */
enum class SegmentCategory(val wire: String, val label: String) {
    Sponsor("sponsor", "sponsor"),
    SelfPromo("selfpromo", "self-promotion"),
    Interaction("interaction", "interaction reminder"),
    Intro("intro", "intro"),
    Outro("outro", "endcards / credits"),
    Preview("preview", "preview / recap"),
    Filler("filler", "filler / tangent"),
    MusicOffTopic("music_offtopic", "non-music section"),
    ;

    companion object {
        fun fromWire(wire: String): SegmentCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** How a category behaves while its segment plays. */
enum class SkipMode {
    /** Jump over it when playback reaches it (with an Undo chip). */
    Auto,

    /** Offer a "Skip …" button while inside it. */
    Button,

    /** Ignore it. */
    Off,
}

/** Settings as the player needs them; the defaults from the plan. */
data class SegmentSettings(
    val enabled: Boolean = false,
    val modes: Map<SegmentCategory, SkipMode> = defaultSkipModes(),
)

fun defaultSkipModes(): Map<SegmentCategory, SkipMode> = mapOf(
    SegmentCategory.Sponsor to SkipMode.Auto,
    SegmentCategory.SelfPromo to SkipMode.Button,
    SegmentCategory.Interaction to SkipMode.Button,
)

/** A hidden stretch in real video time. */
data class SkipSegment(
    val category: SegmentCategory,
    val startMs: Long,
    val endMs: Long,
)

/**
 * The source video id Youtarr puts in the file name: the last bracketed
 * group of exactly 11 id characters (`Title [abcDEF123456].mp4`).
 * No match → no lookup.
 */
private val FILE_NAME_ID = Regex("""\[([A-Za-z0-9_-]{11})\][^\]\[]*$""")

fun sourceVideoId(fileName: String): String? =
    FILE_NAME_ID.find(fileName)?.groupValues?.get(1)

/**
 * First 4 hex characters of the id's SHA-256 — what the service is asked
 * for. It answers for every video sharing the prefix, so the app must keep
 * only the entry whose videoID matches exactly (see [segmentsFor]).
 */
fun hashPrefix(videoId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(videoId.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(4)

// ---- Response parsing (shape verified against the live API 2026-10-21;
// unknown fields ignored, made-up fixtures in tests) ----

@Serializable
private data class VideoSegmentsDto(
    @SerialName("videoID") val videoId: String,
    val segments: List<RawSegmentDto> = emptyList(),
)

@Serializable
private data class RawSegmentDto(
    val category: String = "",
    val actionType: String = "",
    /** The live shape: a [start, end] pair of seconds. */
    val segment: List<Double> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * The segments of [videoId] from a `skipSegments/{prefix}` response: only
 * that exact id, only wanted categories, only jump-over actions.
 */
fun segmentsFor(body: String, videoId: String, wanted: Set<SegmentCategory>): List<SkipSegment> =
    runCatching { json.decodeFromString<List<VideoSegmentsDto>>(body) }
        .getOrNull()
        .orEmpty()
        .filter { it.videoId == videoId }
        .flatMap { it.segments }
        .mapNotNull { s ->
            val category = SegmentCategory.fromWire(s.category) ?: return@mapNotNull null
            if (category !in wanted) return@mapNotNull null
            // Only jump-over timings; mute / full-video actions are not ours
            // to act on. (Absent actionType means skip for the old entries.)
            if (s.actionType.isNotBlank() && s.actionType != "skip") return@mapNotNull null
            val start = s.segment.getOrNull(0) ?: return@mapNotNull null
            val end = s.segment.getOrNull(1) ?: return@mapNotNull null
            SkipSegment(category, (start * 1000).toLong(), (end * 1000).toLong())
        }

// ---- Decisions on the loaded video (player-side rules) ----

/** Segments worth showing / acting on: mode on, at least a second long,
 * and not one ending at the very end (that stretch is the ending — let the
 * video finish, mark watched and roll on to the next as usual). */
fun visibleSegments(
    fetched: List<SkipSegment>,
    modes: Map<SegmentCategory, SkipMode>,
    durationMs: Long,
): List<SkipSegment> = fetched.filter { seg ->
    (modes[seg.category] ?: SkipMode.Off) != SkipMode.Off &&
        seg.endMs - seg.startMs >= 1_000L &&
        !(durationMs > 0 && seg.endMs >= durationMs - 1_000L)
}

/** The segment playing at [positionMs], or null. */
fun segmentAt(segments: List<SkipSegment>, positionMs: Long): SkipSegment? =
    segments.firstOrNull { it.startMs <= positionMs && positionMs < it.endMs }

/**
 * The segment with a live "Skip …" button at [positionMs]: any enabled
 * segment the playhead is inside. Normal playback never sits inside a
 * jump-over (Auto) segment — it was skipped at its start — so being in one
 * means the user seeked or resumed into it, or Undo brought them back: the
 * choice is theirs, so the button offers it.
 */
fun skipButtonFor(
    segments: List<SkipSegment>,
    modes: Map<SegmentCategory, SkipMode>,
    positionMs: Long,
): SkipSegment? {
    val seg = segmentAt(segments, positionMs) ?: return null
    return seg.takeIf { (modes[seg.category] ?: SkipMode.Off) != SkipMode.Off }
}

/**
 * Jump-over segments to schedule a delivery at: every Auto one except
 * those Undo left alone for this viewing. A delivery only fires when
 * playback runs across the start, so one behind the playhead waits until
 * a seek back brings playback up to it again.
 */
fun autoSkipTargets(
    segments: List<SkipSegment>,
    modes: Map<SegmentCategory, SkipMode>,
    undone: Set<SkipSegment>,
): List<SkipSegment> = segments.filter { seg ->
    (modes[seg.category] ?: SkipMode.Off) == SkipMode.Auto && seg !in undone
}

/**
 * A scheduled delivery arriving while [positionMs] is nowhere near the
 * segment start is stale (another video loaded, a seek moved past): ignore
 * it rather than jumping the playhead across the video.
 */
fun staleAutoSkip(segment: SkipSegment, positionMs: Long): Boolean =
    positionMs < segment.startMs - AUTO_SKIP_TOLERANCE_MS ||
        positionMs >= segment.endMs

/** How late a scheduled delivery may still act on its segment. */
const val AUTO_SKIP_TOLERANCE_MS = 1_500L

/** "Skipped sponsor · Undo" for the segment just jumped over. */
data class SkipNotice(val segment: SkipSegment) {
    val label: String get() = segment.category.label

    /** Where Undo jumps back to. */
    val restoreMs: Long get() = segment.startMs
}

/** How long the Undo chip stays offered after an automatic skip. */
const val SKIP_NOTICE_MS = 5_000L

/**
 * The source id a playback of [item] should look segments up for, or null
 * for no lookup (setting off, every category off, or no id in the file
 * name). The lookup asks for every category, so a category switched on
 * mid-video takes effect without asking again.
 */
fun segmentLookupFor(item: ItemDto, settings: SegmentSettings): String? {
    if (!settings.enabled) return null
    if (settings.modes.values.none { it != SkipMode.Off }) return null
    val file = item.Media.firstOrNull()?.Part?.firstOrNull()?.file ?: return null
    return sourceVideoId(file)
}
