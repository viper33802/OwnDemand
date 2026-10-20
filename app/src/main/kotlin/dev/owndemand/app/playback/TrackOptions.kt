package dev.owndemand.app.playback

import androidx.media3.common.Tracks
import java.util.Locale

/** One selectable subtitle or audio track, as the player sees it. */
data class TrackOption(
    /** Index into [Tracks.groups]. */
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

/** Plain description of one track, so labelling is testable without Android. */
data class TrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val type: Int,
    val label: String?,
    val language: String?,
    val supported: Boolean,
    val selected: Boolean,
)

/** The player's supported tracks of [trackType] (C.TRACK_TYPE_TEXT / C.TRACK_TYPE_AUDIO). */
fun trackOptions(tracks: Tracks, trackType: Int): List<TrackOption> =
    trackOptions(
        tracks.groups.flatMapIndexed { g, group ->
            (0 until group.length).map { t ->
                val f = group.getTrackFormat(t)
                TrackInfo(g, t, group.type, f.label, f.language, group.isTrackSupported(t), group.isTrackSelected(t))
            }
        },
        trackType,
    )

/**
 * Labels for a menu: the track's own label, else its language's display
 * name, else "Track N".
 */
fun trackOptions(tracks: List<TrackInfo>, trackType: Int): List<TrackOption> {
    val out = ArrayList<TrackOption>()
    for (t in tracks) {
        if (t.type != trackType || !t.supported) continue
        val languageName = t.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
        out += TrackOption(
            groupIndex = t.groupIndex,
            trackIndex = t.trackIndex,
            label = t.label?.takeIf { it.isNotBlank() } ?: languageName ?: "Track ${out.size + 1}",
            language = t.language,
            selected = t.selected,
        )
    }
    return out
}
