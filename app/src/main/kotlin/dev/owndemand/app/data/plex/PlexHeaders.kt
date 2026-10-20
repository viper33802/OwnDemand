package dev.owndemand.app.data.plex

import android.os.Build

/**
 * The standard `X-Plex-*` identification headers, shared by the OkHttp API
 * client and the Media3 playback data source. PMS returns **400** for the
 * transcode universal endpoints without them (verified in the M3 HLS fix).
 * Values contain no credentials; the token is added separately.
 */
object PlexHeaders {
    const val PRODUCT = "OwnDemand"
    const val PRODUCT_VERSION = "0.3.1"

    fun identification(clientIdentifier: String): Map<String, String> = mapOf(
        "X-Plex-Product" to PRODUCT,
        "X-Plex-Version" to PRODUCT_VERSION,
        "X-Plex-Client-Identifier" to clientIdentifier,
        "X-Plex-Device" to "Android",
        "X-Plex-Device-Name" to Build.MODEL,
        "X-Plex-Platform" to "Android",
        "X-Plex-Platform-Version" to Build.VERSION.SDK_INT.toString(),
    )
}
