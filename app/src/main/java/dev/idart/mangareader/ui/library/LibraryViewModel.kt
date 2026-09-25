package dev.idart.mangareader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.library.model.LibraryManga
import dev.idart.mangareader.domain.manga.interactor.GetLibraryMangas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Content(val items: List<LibraryManga>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}

class LibraryViewModel(
    private val getLibraryMangas: GetLibraryMangas,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            getLibraryMangas.awaitAsFlow()
                .catch { e -> _state.value = LibraryUiState.Error(e.message ?: "Erro desconhecido") }
                .collect { items -> _state.value = LibraryUiState.Content(items) }
        }
    }
}
