package dev.owndemand.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.owndemand.app.domain.PlexLibrary
import dev.owndemand.app.domain.PlexServer
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Snapshot of persisted app state; tokens are decrypted on read. */
data class AppSettings(
    val clientIdentifier: String,
    val accountToken: String?,
    val server: PlexServer?,
    val library: PlexLibrary?,
    /** Maximum quality on Wi-Fi / unmetered networks (Chunk 3). */
    val qualityWifi: dev.owndemand.app.domain.VideoQuality = dev.owndemand.app.domain.VideoQuality.Original,
    /** Maximum quality on mobile / metered networks (Chunk 3). */
    val qualityMobile: dev.owndemand.app.domain.VideoQuality = dev.owndemand.app.domain.VideoQuality.Q720,
    /** Play the first Up-next video after a 5 s countdown (on by default). */
    val autoplay: Boolean = true,
    /** Leaving the app while a video plays shrinks it into a floating window (Chunk 4). */
    val pictureInPicture: Boolean = true,
    /** Keep playing (audio) after leaving the app, with notification controls (Chunk 4). */
    val backgroundPlay: Boolean = true,
    /** Plex account name, shown in Settings (user direction 2026-10-16). */
    val accountUsername: String? = null,
    /** Text-size multiplier over the system font scale; 1 = as-is (user
     * direction 2026-10-16: some prefer larger, keep the default untouched). */
    val fontScale: Float = 1f,
    /**
     * Videos removed from Continue watching: ratingKey -> resume offset at
     * the time. A video shows again once its progress moves on.
     */
    val hiddenContinue: Map<String, Long> = emptyMap(),
) {
    val isSignedIn: Boolean get() = accountToken != null
    val hasServer: Boolean get() = server != null
    val hasLibrary: Boolean get() = library != null
    val isReady: Boolean get() = isSignedIn && hasServer && hasLibrary
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("owndemand_settings")

/** Hidden Continue-watching entries kept (oldest dropped beyond this). */
private const val MAX_HIDDEN = 200

/**
 * Persistent configuration. Tokens are stored only as Keystore-encrypted
 * blobs (see [TokenStore]); the client identifier is a random UUID created
 * once per install and reused for all Plex requests, as Plex requires.
 */
class SettingsRepository(
    context: Context,
    private val tokenStore: TokenStore,
) {
    private object Keys {
        val CLIENT_ID = stringPreferencesKey("client_identifier")
        val ACCOUNT_TOKEN = byteArrayPreferencesKey("account_token")
        val SERVER_MACHINE_ID = stringPreferencesKey("server_machine_id")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        val SERVER_TOKEN = byteArrayPreferencesKey("server_token")
        /** All HTTPS addresses, best first, newline-separated. */
        val SERVER_CONNECTIONS = stringPreferencesKey("server_connections")
        val LIBRARY_KEY = stringPreferencesKey("library_key")
        val LIBRARY_UUID = stringPreferencesKey("library_uuid")
        val LIBRARY_TITLE = stringPreferencesKey("library_title")
        val LIBRARY_TYPE = stringPreferencesKey("library_type")
        /** Pre-Chunk-3 on/off data saver; only read once, to migrate. */
        val DATA_SAVER = androidx.datastore.preferences.core.booleanPreferencesKey("data_saver")
        val QUALITY_WIFI = stringPreferencesKey("quality_wifi")
        val QUALITY_MOBILE = stringPreferencesKey("quality_mobile")
        val AUTOPLAY = androidx.datastore.preferences.core.booleanPreferencesKey("autoplay")
        val PICTURE_IN_PICTURE = androidx.datastore.preferences.core.booleanPreferencesKey("picture_in_picture")
        val BACKGROUND_PLAY = androidx.datastore.preferences.core.booleanPreferencesKey("background_play")
        val ACCOUNT_USERNAME = stringPreferencesKey("account_username")
        val FONT_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("font_scale")
        /** "ratingKey:offsetMs" entries — see [AppSettings.hiddenContinue]. */
        val HIDDEN_CONTINUE = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_continue_watching")
    }

    private val store = context.applicationContext.dataStore

    /**
     * Live settings snapshot. [ensureClientIdentifier] should be called once
     * at startup so `clientIdentifier` is non-blank before any Plex request.
     */
    val settings: Flow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                clientIdentifier = prefs[Keys.CLIENT_ID].orEmpty(),
                accountToken = prefs[Keys.ACCOUNT_TOKEN]?.let { tokenStore.decrypt(it) },
                server = readServer(prefs),
                library = readLibrary(prefs),
                qualityWifi = dev.owndemand.app.domain.VideoQuality.fromName(prefs[Keys.QUALITY_WIFI])
                    ?: dev.owndemand.app.domain.VideoQuality.Original,
                qualityMobile = dev.owndemand.app.domain.VideoQuality.fromName(prefs[Keys.QUALITY_MOBILE])
                    ?: dev.owndemand.app.domain.VideoQuality.Q720,
                autoplay = prefs[Keys.AUTOPLAY] ?: true,
                pictureInPicture = prefs[Keys.PICTURE_IN_PICTURE] ?: true,
                backgroundPlay = prefs[Keys.BACKGROUND_PLAY] ?: true,
                accountUsername = prefs[Keys.ACCOUNT_USERNAME],
                fontScale = prefs[Keys.FONT_SCALE] ?: 1f,
                hiddenContinue = prefs[Keys.HIDDEN_CONTINUE].orEmpty().mapNotNull { entry ->
                    val key = entry.substringBefore(':')
                    entry.substringAfter(':', "").toLongOrNull()?.let { key to it }
                }.toMap(),
            )
        }

    /** Creates (once) and returns the stable per-install client identifier. */
    suspend fun ensureClientIdentifier(): String {
        store.edit { prefs ->
            if (prefs[Keys.CLIENT_ID] == null) {
                prefs[Keys.CLIENT_ID] = UUID.randomUUID().toString()
            }
        }
        return store.data.first()[Keys.CLIENT_ID].orEmpty()
    }

    /** Removes a video from Continue watching until its progress changes. */
    suspend fun hideFromContinueWatching(ratingKey: String, viewOffsetMs: Long) = store.edit { prefs ->
        val kept = prefs[Keys.HIDDEN_CONTINUE].orEmpty()
            .filterNot { it.substringBefore(':') == ratingKey }
            .takeLast(MAX_HIDDEN - 1)
        prefs[Keys.HIDDEN_CONTINUE] = (kept + "$ratingKey:$viewOffsetMs").toSet()
    }

    /** The stored account token, or null when signed out. */
    suspend fun currentAccountToken(): String? = settings.first().accountToken

    suspend fun setAccountToken(token: String) =
        store.edit { it[Keys.ACCOUNT_TOKEN] = tokenStore.encrypt(token) }

    /** Plex account name for the Settings header (not a credential). */
    suspend fun setAccountUsername(name: String) =
        store.edit { it[Keys.ACCOUNT_USERNAME] = name }

    suspend fun setServer(server: PlexServer) = store.edit { prefs ->
        prefs[Keys.SERVER_MACHINE_ID] = server.machineIdentifier
        prefs[Keys.SERVER_NAME] = server.name
        prefs[Keys.SERVER_BASE_URL] = server.baseUrl
        prefs[Keys.SERVER_TOKEN] = tokenStore.encrypt(server.accessToken)
        prefs[Keys.SERVER_CONNECTIONS] = server.connections.joinToString("\n")
    }

    /** Switches the address in use (one of the server's [PlexServer.connections]). */
    suspend fun setServerAddress(baseUrl: String) = store.edit { prefs ->
        if (baseUrl.startsWith("https://")) prefs[Keys.SERVER_BASE_URL] = baseUrl
    }

    /**
     * Replaces the server's address list (and its access token, which Plex
     * may rotate) after a fresh read from plex.tv; the address in use is kept
     * unless it is no longer advertised.
     */
    suspend fun updateServerConnections(connections: List<String>, accessToken: String) =
        store.edit { prefs ->
            val https = connections.filter { it.startsWith("https://") }
            if (https.isEmpty()) return@edit
            prefs[Keys.SERVER_CONNECTIONS] = https.joinToString("\n")
            if (prefs[Keys.SERVER_BASE_URL] !in https) prefs[Keys.SERVER_BASE_URL] = https.first()
            if (accessToken.isNotBlank()) prefs[Keys.SERVER_TOKEN] = tokenStore.encrypt(accessToken)
        }

    suspend fun setLibrary(library: PlexLibrary) = store.edit { prefs ->
        prefs[Keys.LIBRARY_KEY] = library.key
        prefs[Keys.LIBRARY_UUID] = library.uuid
        prefs[Keys.LIBRARY_TITLE] = library.title
        prefs[Keys.LIBRARY_TYPE] = library.type
    }

    /** Clears credentials and selections; keeps the client identifier. */
    suspend fun signOut() = store.edit { prefs ->
        prefs.remove(Keys.ACCOUNT_TOKEN)
        prefs.remove(Keys.ACCOUNT_USERNAME)
        prefs.remove(Keys.SERVER_MACHINE_ID)
        prefs.remove(Keys.SERVER_NAME)
        prefs.remove(Keys.SERVER_BASE_URL)
        prefs.remove(Keys.SERVER_TOKEN)
        prefs.remove(Keys.SERVER_CONNECTIONS)
        clearLibraryKeys(prefs)
    }

    /** Drops only the library selection (e.g. when the library disappears). */
    suspend fun clearLibrary() = store.edit { clearLibraryKeys(it) }

    /**
     * One-time move from the old data-saver switch to the two quality
     * lists: data saver on → 480p for both; an existing install otherwise
     * keeps Original for both (nothing changes on upgrade); a fresh install
     * gets the defaults (Wi-Fi Original, mobile 720p).
     */
    suspend fun ensureQualitySettings() = store.edit { prefs ->
        if (prefs[Keys.QUALITY_WIFI] != null && prefs[Keys.QUALITY_MOBILE] != null) return@edit
        val (wifi, mobile) = initialQuality(
            dataSaverWasOn = prefs[Keys.DATA_SAVER] == true,
            existingInstall = prefs[Keys.SERVER_MACHINE_ID] != null,
        )
        prefs[Keys.QUALITY_WIFI] = wifi.name
        prefs[Keys.QUALITY_MOBILE] = mobile.name
        prefs.remove(Keys.DATA_SAVER)
    }

    suspend fun setQuality(wifi: Boolean, quality: dev.owndemand.app.domain.VideoQuality) =
        store.edit { it[if (wifi) Keys.QUALITY_WIFI else Keys.QUALITY_MOBILE] = quality.name }

    suspend fun setAutoplay(enabled: Boolean) = store.edit { prefs ->
        prefs[Keys.AUTOPLAY] = enabled
    }

    suspend fun setPictureInPicture(enabled: Boolean) = store.edit { prefs ->
        prefs[Keys.PICTURE_IN_PICTURE] = enabled
    }

    suspend fun setBackgroundPlay(enabled: Boolean) = store.edit { prefs ->
        prefs[Keys.BACKGROUND_PLAY] = enabled
    }

    /** App-wide text-size multiplier; 1f clears it back to the system scale. */
    suspend fun setFontScale(scale: Float) = store.edit { prefs ->
        val clamped = scale.coerceIn(0.85f, 1.5f)
        if (clamped == 1f) prefs.remove(Keys.FONT_SCALE) else prefs[Keys.FONT_SCALE] = clamped
    }

    /** Drops the server selection but keeps the account token, so the
     * connect flow can offer the other servers on the account. */
    suspend fun clearServer() = store.edit { prefs ->
        prefs.remove(Keys.SERVER_MACHINE_ID)
        prefs.remove(Keys.SERVER_NAME)
        prefs.remove(Keys.SERVER_BASE_URL)
        prefs.remove(Keys.SERVER_TOKEN)
        prefs.remove(Keys.SERVER_CONNECTIONS)
        clearLibraryKeys(prefs)
    }

    private fun clearLibraryKeys(prefs: MutablePreferences) {
        prefs.remove(Keys.LIBRARY_KEY)
        prefs.remove(Keys.LIBRARY_UUID)
        prefs.remove(Keys.LIBRARY_TITLE)
        prefs.remove(Keys.LIBRARY_TYPE)
    }

    private suspend fun readServer(prefs: Preferences): PlexServer? {
        val machineId = prefs[Keys.SERVER_MACHINE_ID] ?: return null
        val name = prefs[Keys.SERVER_NAME] ?: return null
        // HTTPS only; a server saved by an older build over http must be re-picked.
        val baseUrl = prefs[Keys.SERVER_BASE_URL]?.takeIf { it.startsWith("https://") } ?: return null
        val token = prefs[Keys.SERVER_TOKEN]?.let { tokenStore.decrypt(it) } ?: return null
        val connections = prefs[Keys.SERVER_CONNECTIONS].orEmpty()
            .split('\n').filter { it.startsWith("https://") }
        return PlexServer(machineId, name, baseUrl, token, connections)
    }

    private fun readLibrary(prefs: Preferences): PlexLibrary? {
        val key = prefs[Keys.LIBRARY_KEY] ?: return null
        val uuid = prefs[Keys.LIBRARY_UUID] ?: return null
        val title = prefs[Keys.LIBRARY_TITLE] ?: return null
        val type = prefs[Keys.LIBRARY_TYPE] ?: return null
        return PlexLibrary(key, uuid, title, type)
    }
}

/** First quality settings (see [SettingsRepository.ensureQualitySettings]); pure for tests. */
fun initialQuality(
    dataSaverWasOn: Boolean,
    existingInstall: Boolean,
): Pair<dev.owndemand.app.domain.VideoQuality, dev.owndemand.app.domain.VideoQuality> = when {
    dataSaverWasOn -> dev.owndemand.app.domain.VideoQuality.Q480 to dev.owndemand.app.domain.VideoQuality.Q480
    existingInstall -> dev.owndemand.app.domain.VideoQuality.Original to dev.owndemand.app.domain.VideoQuality.Original
    else -> dev.owndemand.app.domain.VideoQuality.Original to dev.owndemand.app.domain.VideoQuality.Q720
}
