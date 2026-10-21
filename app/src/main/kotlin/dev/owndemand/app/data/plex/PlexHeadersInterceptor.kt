package dev.owndemand.app.data.plex

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the standard `X-Plex-*` identification headers and JSON accept header
 * to every request. The token is added per-request by the clients, never here,
 * so it can be rotated or absent without rebuilding the OkHttp client.
 *
 * Header values must never contain credentials; the token is not part of this.
 */
class PlexHeadersInterceptor(
    private val clientIdentifier: () -> String,
    private val product: String = "OwnDemand",
    private val productVersion: String = "0.4.0",
    private val deviceName: String,
    private val platformVersion: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("X-Plex-Product", product)
            .header("X-Plex-Version", productVersion)
            .header("X-Plex-Client-Identifier", clientIdentifier())
            .header("X-Plex-Device", "Android")
            .header("X-Plex-Device-Name", deviceName)
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Platform-Version", platformVersion)
            .build()
        return chain.proceed(request)
    }
}
