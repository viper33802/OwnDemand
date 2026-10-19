package dev.owndemand.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
import dev.owndemand.app.data.plex.dto.CollectionDto
import dev.owndemand.app.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings("", null, null, null))

    // Navigation state lives here (not in composables) so it survives the
    // watch screen replacing the shell: back from playback returns to the
    // same tab and the same channel page.
    val selectedTab = MutableStateFlow(0)
    val openChannel = MutableStateFlow<CollectionDto?>(null)

    // The watch screen is an overlay inside the shell (user direction
    // 2026-10-16): the bottom menu stays visible while playing portrait,
    // so the shell needs to know about watch + fullscreen state.
    val watchKey = MutableStateFlow<String?>(null)
    val watchFullscreen = MutableStateFlow(false)

    fun play(ratingKey: String) {
        watchFullscreen.value = false
        watchKey.value = ratingKey
    }

    fun closeWatch() {
        watchFullscreen.value = false
        watchKey.value = null
    }

    fun setWatchFullscreen(fullscreen: Boolean) {
        watchFullscreen.value = fullscreen
    }

    fun selectTab(index: Int) {
        // Tapping a tab while watching leaves playback to the floating
        // miniplayer (browse-while-watching, spec §8).
        selectedTab.value = index
        _searchOpen.value = false
        closeWatch()
    }

    /** Search is a Home action (overlay), not a bottom-nav tab (user
     * direction 2026-10-16). */
    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    fun openSearch() {
        _searchOpen.value = true
    }

    fun closeSearch() {
        _searchOpen.value = false
    }

    /** Channel match from search → the Channels tab's detail page. */
    fun openChannelFromSearch(channel: dev.owndemand.app.data.plex.dto.CollectionDto) {
        selectTab(1)
        openChannel.value = channel
    }

    /** Channel link from the watch screen: resolve the channel name
     * against the collections cache and open that channel's page. */
    fun openChannelByName(name: String) {
        viewModelScope.launch {
            container.libraryIndex.ensureLoaded(container)
            val match = container.libraryIndex.collections.value
                ?.firstOrNull { it.title.equals(name, ignoreCase = true) }
                ?: return@launch
            selectTab(1)
            openChannel.value = match
        }
    }

    init {
        // Keep the container pointed at the current server + library so the
        // feed and playback layers can build repositories on demand.
        viewModelScope.launch {
            container.settingsRepository.settings.collect { s ->
                container.currentServer = s.server
                container.currentSectionKey = s.library?.key
                // Keep the live player in sync with the quality preference.
                runCatching { container.playbackManager().dataSaver = s.dataSaver }
            }
        }
        // Server address: confirm it answers, then refresh the address list
        // from plex.tv (installs predating the list stored only one address;
        // home internet addresses change) and re-confirm against it.
        viewModelScope.launch {
            val s = container.settingsRepository.settings.first { it.server != null }
            if (container.currentServer == null) container.currentServer = s.server
            container.recheckConnection()
            container.connections.refreshFromPlexTv()
            container.recheckConnection()
        }
        // Installs predating the username capture: fetch it once, best-effort.
        viewModelScope.launch {
            val s = container.settingsRepository.settings.first()
            if (s.isSignedIn && s.accountUsername == null) {
                val token = s.accountToken ?: return@launch
                runCatching { container.plexTvClient.account(token).username }
                    .getOrNull()?.let { container.settingsRepository.setAccountUsername(it) }
            }
        }
    }

    private val _signingOut = MutableStateFlow(false)
    val signingOut: StateFlow<Boolean> = _signingOut

    fun signOut() {
        viewModelScope.launch {
            _signingOut.value = true
            container.releasePlayback()
            container.libraryIndex.invalidate()
            container.currentServer = null
            container.currentSectionKey = null
            container.settingsRepository.signOut()
            _signingOut.value = false
        }
    }

    /** Drops the library choice; root navigation lands on the library picker. */
    fun changeLibrary() {
        viewModelScope.launch {
            container.releasePlayback()
            container.libraryIndex.invalidate()
            container.settingsRepository.clearLibrary()
        }
    }

    /** Data saver playback mode (M6); applies from the next video. */
    fun setDataSaver(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDataSaver(enabled) }
    }

    /** App-wide text size, from the Settings slider. */
    fun setFontScale(scale: Float) {
        viewModelScope.launch { container.settingsRepository.setFontScale(scale) }
    }

    /** Drops the server choice (keeps the account token); the connect flow
     * re-runs discovery and lists every server on the account. */
    fun switchServer() {
        viewModelScope.launch {
            container.releasePlayback()
            container.libraryIndex.invalidate()
            container.currentServer = null
            container.currentSectionKey = null
            container.settingsRepository.clearServer()
        }
    }
}
