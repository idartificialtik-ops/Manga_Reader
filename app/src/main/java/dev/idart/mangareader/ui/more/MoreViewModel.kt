package dev.idart.mangareader.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.history.repository.HistoryRepository
import dev.idart.mangareader.domain.manga.interactor.GetFavorites
import dev.idart.mangareader.domain.source.service.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MoreUiState(
    val sourceCount: Int = 0,
    val favoriteCount: Int = 0,
    val totalReadMinutes: Long = 0L,
)

class MoreViewModel(
    sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MoreUiState(sourceCount = sourceManager.getCatalogueSources().size))
    val state: StateFlow<MoreUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val favorites = getFavorites.await().size
            val readSeconds = historyRepository.getTotalReadDuration()
            _state.value = _state.value.copy(
                favoriteCount = favorites,
                totalReadMinutes = readSeconds / 60,
            )
        }
    }
}
