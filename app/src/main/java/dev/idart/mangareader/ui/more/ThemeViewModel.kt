package dev.idart.mangareader.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.ui.theme.ThemeMode
import dev.idart.mangareader.ui.theme.ThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThemeViewModel(
    private val themeRepository: ThemeRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themeRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val seedIndex: StateFlow<Int> = themeRepository.seedIndex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val amoled: StateFlow<Boolean> = themeRepository.amoled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themeRepository.setThemeMode(mode)
        }
    }

    fun setSeedIndex(index: Int) {
        viewModelScope.launch {
            themeRepository.setSeedIndex(index)
        }
    }

    fun setAmoled(value: Boolean) {
        viewModelScope.launch {
            themeRepository.setAmoled(value)
        }
    }
}
