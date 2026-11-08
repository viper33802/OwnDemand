package dev.owndemand.app.data.plex

/**
 * Whether this device can decode a video codec (Plex codec name, e.g.
 * "av1") at a size; 0 means unknown. Kept free of Android types so the
 * playback decision stays unit-testable; the real check is
 * `playback.MediaCodecDecoderSupport`.
 */
fun interface DecoderSupport {
    fun canDecode(codec: String, width: Int, height: Int): Boolean
}
