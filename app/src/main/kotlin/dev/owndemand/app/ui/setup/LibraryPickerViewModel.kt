package dev.owndemand.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.owndemand.app.AppContainer
import dev.owndemand.app.domain.PlexLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface LibraryPickerState {
    data object Loading : LibraryPickerState
    data class Ready(val libraries: List<PlexLibrary>) : LibraryPickerState
    data class Error(val message: String) : LibraryPickerState
}

class LibraryPickerViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<LibraryPickerState>(LibraryPickerState.Loading)
    val state: StateFlow<LibraryPickerState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = LibraryPickerState.Loading
        viewModelScope.launch {
            val server = container.settingsRepository.settings.first().server
            if (server == null) {
                _state.value = LibraryPickerState.Error("No server selected yet.")
                return@launch
            }
            try {
                _state.value = LibraryPickerState.Ready(container.pmsClient(server).sections())
            } catch (e: Exception) {
                _state.value = LibraryPickerState.Error(
                    userMessage(e, "Could not list libraries from ${server.name}."),
                )
            }
        }
    }

    fun select(library: PlexLibrary) {
        viewModelScope.launch { container.settingsRepository.setLibrary(library) }
    }
}
