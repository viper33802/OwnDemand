package dev.owndemand.app.domain

/**
 * Maximum playback quality (Chunk 3; replaces the old on/off data saver).
 * [Original] plays the file as-is when the device can decode it; a cap
 * plays videos at or below it as-is and has Plex's transcoder bring taller
 * ones down to it. The app keeps one choice for Wi-Fi and one for mobile
 * (metered) data.
 */
enum class VideoQuality(
    val label: String,
    /** Compact label for the player's quality button. */
    val shortLabel: String,
    /** Tallest video height (px) played as-is; null = no limit. */
    val maxHeight: Int?,
    /** Extra PMS universal-transcode parameters for this cap. */
    val transcodeParams: String,
) {
    Original("Original", "Orig", null, ""),
    Q1080("1080p", "1080p", 1080, "&videoResolution=1920x1080&maxVideoBitrate=8000"),
    Q720("720p", "720p", 720, "&videoResolution=1280x720&maxVideoBitrate=4000"),

    // Verified against a live server 2026-10-16 (480p/1200k yields
    // RESOLUTION=718x404, BANDWIDTH=895000).
    Q480("480p (data saver)", "480p", 480, "&maxVideoBitrate=1200&videoQuality=480p&audioBitrate=128"),
    ;

    companion object {
        fun fromName(name: String?): VideoQuality? = entries.firstOrNull { it.name == name }
    }
}
