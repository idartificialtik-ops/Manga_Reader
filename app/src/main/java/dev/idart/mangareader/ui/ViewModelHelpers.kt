package dev.idart.mangareader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Creates a [ViewModel] scoped to the current back-stack entry, resolving its
 * dependencies from Injekt via [creator].
 */
@Composable
inline fun <reified VM : ViewModel> injektViewModel(
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner provided"
    },
    key: String? = null,
    crossinline creator: () -> VM,
): VM {
    val factory = remember(key) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
        }
    }
    return viewModel(viewModelStoreOwner = viewModelStoreOwner, key = key, factory = factory)
}
