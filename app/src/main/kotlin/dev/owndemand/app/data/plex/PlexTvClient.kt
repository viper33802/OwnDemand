package dev.owndemand.app.data.plex

import dev.owndemand.app.data.plex.dto.AccountResponse
import dev.owndemand.app.data.plex.dto.PinResponse
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlexTvException(message: String) : IOException(message)

/**
 * plex.tv account API: PIN sign-in, account check, server discovery.
 * All calls are HTTPS; the account token is sent as a header only.
 */
class PlexTvClient(
    private val http: OkHttpClient,
) {
    companion object {
        private const val PLEX_TV = "https://plex.tv"
        private const val CLIENTS_PLEX_TV = "https://clients.plex.tv"

        /**
         * Builds the plex.tv Auth App URL the user opens to claim a PIN.
         * Fragment parameters are URL-encoded per Plex's documented flow.
         */
        fun buildAuthUrl(
            clientIdentifier: String,
            code: String,
            product: String = "OwnDemand",
        ): String =
            "https://app.plex.tv/auth#?" +
                "clientID=${encode(clientIdentifier)}" +
                "&code=${encode(code)}" +
                "&context%5Bdevice%5D%5Bproduct%5D=${encode(product)}"

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")
    }

    /** Requests a new sign-in PIN. `strong` gives a longer-lived code. */
    suspend fun createPin(): PinResponse =
        fetch("$PLEX_TV/api/v2/pins?strong=true".toHttpUrl(), token = null, method = "POST")
            .let { PlexJson.decodeFromString<PinResponse>(it) }

    /** Polls a PIN; [PinResponse.authToken] is non-null once the user has signed in. */
    suspend fun pollPin(pinId: Long): PinResponse =
        fetch("$PLEX_TV/api/v2/pins/$pinId".toHttpUrl(), token = null)
            .let { PlexJson.decodeFromString<PinResponse>(it) }

    /** Validates a stored account token (200 = valid). Throws on 401. */
    suspend fun account(token: String): AccountResponse =
        fetch("$PLEX_TV/api/v2/user".toHttpUrl(), token)
            .let { PlexJson.decodeFromString<AccountResponse>(it) }

    /** Lists the account's devices; filter to owned servers via ServerDiscovery. */
    suspend fun resourcesJson(token: String): String =
        fetch("$CLIENTS_PLEX_TV/api/v2/resources?includeHttps=1".toHttpUrl(), token)

    private suspend fun fetch(
        url: HttpUrl,
        token: String?,
        method: String = "GET",
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .method(method, if (method == "POST") ByteArray(0).toRequestBody(null) else null)
        if (token != null) request.header("X-Plex-Token", token)

        http.newCall(request.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> body
                resp.code == 401 -> throw PlexTvException("Plex rejected the credentials (401).")
                else -> throw PlexTvException("plex.tv request failed (${resp.code}).")
            }
        }
    }
}
