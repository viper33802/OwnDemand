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
    /** Transcode everything to 480p/1200k instead of direct-playing (M6). */
    val dataSaver: Boolean = false,
    /** Plex account name, shown in Settings (user direction 2026-10-16). */
    val accountUsername: String? = null,
    /** Text-size multiplier over the system font scale; 1 = as-is (user
     * direction 2026-10-16: some prefer larger, keep the default untouched). */
    val fontScale: Float = 1f,
) {
    val isSignedIn: Boolean get() = accountToken != null
    val hasServer: Boolean get() = server != null
    val hasLibrary: Boolean get() = library != null
    val isReady: Boolean get() = isSignedIn && hasServer && hasLibrary
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("owndemand_settings")

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
        val LIBRARY_KEY = stringPreferencesKey("library_key")
        val LIBRARY_UUID = stringPreferencesKey("library_uuid")
        val LIBRARY_TITLE = stringPreferencesKey("library_title")
        val LIBRARY_TYPE = stringPreferencesKey("library_type")
        val DATA_SAVER = androidx.datastore.preferences.core.booleanPreferencesKey("data_saver")
        val ACCOUNT_USERNAME = stringPreferencesKey("account_username")
        val FONT_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("font_scale")
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
                dataSaver = prefs[Keys.DATA_SAVER] == true,
                accountUsername = prefs[Keys.ACCOUNT_USERNAME],
                fontScale = prefs[Keys.FONT_SCALE] ?: 1f,
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
        clearLibraryKeys(prefs)
    }

    /** Drops only the library selection (e.g. when the library disappears). */
    suspend fun clearLibrary() = store.edit { clearLibraryKeys(it) }

    /** Data saver playback mode (M6). */
    suspend fun setDataSaver(enabled: Boolean) = store.edit { prefs ->
        if (enabled) prefs[Keys.DATA_SAVER] = true else prefs.remove(Keys.DATA_SAVER)
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
        return PlexServer(machineId, name, baseUrl, token)
    }

    private fun readLibrary(prefs: Preferences): PlexLibrary? {
        val key = prefs[Keys.LIBRARY_KEY] ?: return null
        val uuid = prefs[Keys.LIBRARY_UUID] ?: return null
        val title = prefs[Keys.LIBRARY_TITLE] ?: return null
        val type = prefs[Keys.LIBRARY_TYPE] ?: return null
        return PlexLibrary(key, uuid, title, type)
    }
}
