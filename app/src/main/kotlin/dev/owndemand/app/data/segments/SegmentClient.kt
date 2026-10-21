package dev.owndemand.app.data.segments

import dev.owndemand.app.playback.SegmentCategory
import dev.owndemand.app.playback.SkipSegment
import dev.owndemand.app.playback.hashPrefix
import dev.owndemand.app.playback.segmentsFor
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The one contact with a third-party service in the whole app, and only
 * while the Skip segments setting is on: a read-only lookup of SponsorBlock
 * segment timings (data © SponsorBlock, CC BY-NC-SA 4.0).
 *
 * Deliberately its own client with nothing Plex about it: no X-Plex-*
 * headers, no token, no server address, no device or account details —
 * just a GET of a 4-character hash prefix, so the service never learns
 * which video is being watched. Any failure means "no segments", silently.
 */
class SegmentClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Segments of exactly this video id; empty on any failure. */
    suspend fun lookup(videoId: String, wanted: List<SegmentCategory>): List<SkipSegment> =
        withContext(Dispatchers.IO) {
            if (wanted.isEmpty()) return@withContext emptyList()
            val categories = wanted.joinToString(",", "[", "]") { "\"${it.wire}\"" }
            val url = HOST + "/api/skipSegments/" + hashPrefix(videoId) +
                "?categories=" + URLEncoder.encode(categories, "UTF-8")
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    // 404 simply means "no segments for this hash prefix".
                    if (!response.isSuccessful) return@use emptyList<SkipSegment>()
                    segmentsFor(response.body.string(), videoId, wanted.toSet())
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    companion object {
        /** The only host this ever talks to (named in the setting's text). */
        const val HOST = "https://sponsor.ajay.app"
    }
}
