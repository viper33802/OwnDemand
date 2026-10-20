package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.plex.dto.CollectionsResponse
import dev.owndemand.app.data.plex.dto.IdentityResponse
import dev.owndemand.app.data.plex.dto.ItemDto
import dev.owndemand.app.data.plex.dto.MediaContainerResponse
import dev.owndemand.app.data.plex.dto.SectionsResponse
import dev.owndemand.app.domain.PlexLibrary
import dev.owndemand.app.domain.PlexServer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PmsException(message: String) : IOException(message)

/**
 * Direct HTTP client for one Plex Media Server. The server access token is
 * sent as a header on every request.
 */
class PmsClient(
    private val http: OkHttpClient,
    private val server: PlexServer,
) {
    val baseUrl: String get() = server.baseUrl

    /** Verifies reachability and identity; throws when the server disagrees. */
    suspend fun verifyIdentity(): IdentityResponse {
        val resp = PlexJson.decodeFromString<IdentityResponse>(fetch(pmsUrl("/identity")))
        val reported = resp.container.machineIdentifier
        if (reported.isNotBlank() && reported != server.machineIdentifier) {
            throw PmsException(
                "Server identity mismatch (expected ${server.machineIdentifier.take(8)}…, " +
                    "got ${reported.take(8)}…).",
            )
        }
        return resp
    }

    /** Lists library sections on this server. */
    suspend fun sections(): List<PlexLibrary> =
        PlexJson.decodeFromString<SectionsResponse>(fetch(pmsUrl("/library/sections")))
            .container.Directory
            .filter { it.uuid.isNotBlank() }
            .map {
                PlexLibrary(
                    key = it.key,
                    uuid = it.uuid,
                    title = it.title,
                    type = it.type,
                )
            }

    /**
     * One page of a section's items, newest first. Returns items plus the
     * server's `totalSize` (falls back to page size when absent).
     */
    suspend fun feed(sectionKey: String, start: Int, size: Int): Pair<List<ItemDto>, Int> {
        val url = pmsUrl("/library/sections/$sectionKey/all")
            .newBuilder()
            .addQueryParameter("sort", "addedAt:desc")
            .build()
        val container = PlexJson.decodeFromString<MediaContainerResponse>(
            fetchPaged(url, start, size),
        ).container
        return container.Metadata to
            (if (container.totalSize > 0) container.totalSize else container.size)
    }

    /**
     * One page of the section's channel collections (M4). Server order is
     * alphabetical by title (verified on this PMS).
     */
    suspend fun collections(
        sectionKey: String,
        start: Int,
        size: Int,
    ): Pair<List<CollectionDto>, Int> {
        val container = PlexJson.decodeFromString<CollectionsResponse>(
            fetchPaged(pmsUrl("/library/sections/$sectionKey/collections"), start, size),
        ).container
        return container.Metadata to
            (if (container.totalSize > 0) container.totalSize else container.size)
    }

    /** Members of a collection. Server order; paged like the feed. */
    suspend fun collectionChildren(
        ratingKey: String,
        start: Int,
        size: Int,
    ): Pair<List<ItemDto>, Int> {
        val container = PlexJson.decodeFromString<MediaContainerResponse>(
            fetchPaged(pmsUrl("/library/collections/$ratingKey/children"), start, size),
        ).container
        return container.Metadata to
            (if (container.totalSize > 0) container.totalSize else container.size)
    }

    /** Full metadata for one item (Media/Part/Stream, summary, viewOffset). */
    suspend fun item(ratingKey: String): ItemDto =
        PlexJson.decodeFromString<MediaContainerResponse>(
            fetch(pmsUrl("/library/metadata/$ratingKey")),
        ).container.Metadata.firstOrNull()
            ?: throw PmsException("Item $ratingKey not found on the server.")

    /**
     * Reports playback state for resume + watched status (verified 200 in
     * the M3 spike). Failures are swallowed — timeline is best-effort.
     */
    suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMs: Long,
        durationMs: Long,
    ): Boolean = try {
        val url = pmsUrl("/:/timeline")
            .newBuilder()
            .addQueryParameter("ratingKey", ratingKey)
            .addQueryParameter("key", "/library/metadata/$ratingKey")
            .addQueryParameter("state", state)
            .addQueryParameter("time", timeMs.toString())
            .addQueryParameter("duration", durationMs.toString())
            .build()
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("X-Plex-Token", server.accessToken)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Marks an item watched (viewCount + 1, resume offset cleared) — PMS
     * `/:/scrobble` with the library identifier, as Plex's own clients and
     * python-plexapi use it. Best-effort like the timeline: failures are
     * swallowed and the next refresh shows the server's real state.
     */
    suspend fun scrobble(ratingKey: String): Boolean = try {
        val url = pmsUrl("/:/scrobble")
            .newBuilder()
            .addQueryParameter("identifier", "com.plexapp.plugins.library")
            .addQueryParameter("key", ratingKey)
            .build()
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("X-Plex-Token", server.accessToken)
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Stops a transcode session (`/video/:/transcode/universal/stop`,
     * verified 200 in the M3 spike) so the server stops converting a
     * stream the player has left. Best-effort.
     */
    suspend fun stopTranscode(session: String): Boolean = try {
        val url = pmsUrl("/video/:/transcode/universal/stop")
            .newBuilder()
            .addQueryParameter("session", session)
            .build()
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("X-Plex-Token", server.accessToken)
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }
    } catch (e: Exception) {
        false
    }

    /** Marks an item unwatched (viewCount and resume offset cleared): PMS `/:/unscrobble`. */
    suspend fun unscrobble(ratingKey: String): Boolean = try {
        val url = pmsUrl("/:/unscrobble")
            .newBuilder()
            .addQueryParameter("identifier", "com.plexapp.plugins.library")
            .addQueryParameter("key", ratingKey)
            .build()
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("X-Plex-Token", server.accessToken)
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }
    } catch (e: Exception) {
        false
    }

    private fun pmsUrl(path: String): HttpUrl = (server.baseUrl + path).toHttpUrl()

    /** GET with X-Plex-Container-* paging headers (feed-style). */
    private suspend fun fetchPaged(url: HttpUrl, start: Int, size: Int): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("X-Plex-Token", server.accessToken)
                .header("X-Plex-Container-Start", start.toString())
                .header("X-Plex-Container-Size", size.toString())
                .build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> body
                    resp.code == 401 -> throw PmsException("The server rejected the access token (401).")
                    else -> throw PmsException("Server request failed (${resp.code}).")
                }
            }
        }

    /** Raw BIF index bytes for a part (whole scrub-preview strip). */
    suspend fun bifBytes(partId: Long): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(pmsUrl("/library/parts/$partId/indexes/sd"))
            .header("X-Plex-Token", server.accessToken)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw PmsException("BIF fetch failed (${resp.code}).")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun fetch(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("X-Plex-Token", server.accessToken)
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> body
                resp.code == 401 -> throw PmsException("The server rejected the access token (401).")
                else -> throw PmsException("Server request failed (${resp.code}).")
            }
        }
    }
}
