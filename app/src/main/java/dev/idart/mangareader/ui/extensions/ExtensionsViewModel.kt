package dev.idart.mangareader.ui.extensions

import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.sources.extension.ExtensionManager
import dev.idart.mangareader.sources.extension.repo.ExtensionInstaller
import dev.idart.mangareader.sources.extension.repo.ExtensionRepo
import dev.idart.mangareader.sources.extension.repo.ExtensionRepoStore
import dev.idart.mangareader.sources.extension.repo.InstallEvents
import dev.idart.mangareader.sources.extension.repo.RepoEntry
import dev.idart.mangareader.sources.extension.repo.RepoExtension
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExtensionsTab { INSTALLED, CATALOG }

sealed interface InstallUiState {
    data object Idle : InstallUiState
    data class Downloading(val progress: Float?) : InstallUiState
    data object Installing : InstallUiState
    data class Error(val message: String) : InstallUiState
}

data class ExtensionsUiState(
    val extensions: List<ExtensionManager.ExtensionInfo> = emptyList(),
    val isLoading: Boolean = true,
)

data class CatalogEntry(
    val repo: RepoEntry,
    val extension: RepoExtension,
)

data class CatalogUiState(
    val entries: List<CatalogEntry> = emptyList(),
    val repos: List<RepoEntry> = emptyList(),
    val repoFilter: String? = null,
    val hideNsfw: Boolean = false,
    val repoWarnings: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val query: String = "",
)

class ExtensionsViewModel(
    private val extensionManager: ExtensionManager,
    private val extensionRepo: ExtensionRepo,
    private val extensionRepoStore: ExtensionRepoStore,
    private val extensionInstaller: ExtensionInstaller,
    private val packageManager: PackageManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ExtensionsUiState())
    val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

    private val _catalog = MutableStateFlow(CatalogUiState())
    val catalog: StateFlow<CatalogUiState> = _catalog.asStateFlow()

    private val _installStates = MutableStateFlow<Map<String, InstallUiState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallUiState>> = _installStates.asStateFlow()

    private val installJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            extensionManager.installed.collect { installed ->
                _state.update { it.copy(extensions = installed, isLoading = false) }
                // Clear "installing" marks for packages that are now present.
                val pkgs = installed.map { it.packageName }.toSet()
                _installStates.update { current ->
                    current.filterKeys { it !in pkgs }
                }
            }
        }
        viewModelScope.launch {
            InstallEvents.results.collect { result ->
                val pkg = result.packageName
                if (result.success) {
                    if (pkg != null) {
                        _installStates.update { it - pkg }
                    }
                    refreshInstalled()
                } else {
                    val message = result.message ?: "Falha na instalação"
                    _installStates.update { current ->
                        if (pkg != null) {
                            current + (pkg to InstallUiState.Error(message))
                        } else {
                            // Failure without a package: fail any row awaiting confirmation.
                            current.mapValues { (_, state) ->
                                if (state is InstallUiState.Installing) {
                                    InstallUiState.Error(message)
                                } else {
                                    state
                                }
                            }
                        }
                    }
                }
            }
        }
        refresh()
        loadCatalog()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            extensionManager.refresh()
        }
    }

    fun loadCatalog(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _catalog.update { it.copy(isLoading = true, error = null) }
            try {
                val repos = extensionRepoStore.currentRepos()
                val hideNsfw = extensionRepoStore.hideNsfw.first()
                val warnings = mutableListOf<String>()
                val entries = repos.flatMap { repo ->
                    runCatching { extensionRepo.fetchIndex(repo.indexUrl, forceRefresh) }
                        .onFailure { e -> warnings += "${repo.name}: ${e.message}" }
                        .getOrDefault(emptyList())
                        .map { CatalogEntry(repo, it) }
                }
                val errors = when {
                    entries.isEmpty() && repos.isNotEmpty() -> warnings.ifEmpty { listOf("Nenhum repositório respondeu") }
                    else -> null
                }
                _catalog.update {
                    it.copy(
                        entries = entries,
                        repos = repos,
                        hideNsfw = hideNsfw,
                        repoWarnings = warnings,
                        isLoading = false,
                        error = errors?.joinToString("\n"),
                    )
                }
            } catch (e: Throwable) {
                _catalog.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Erro ao carregar catálogo",
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        _catalog.update { it.copy(query = query) }
    }

    fun setRepoFilter(repoName: String?) {
        _catalog.update { it.copy(repoFilter = repoName) }
    }

    fun setHideNsfw(hide: Boolean) {
        viewModelScope.launch {
            extensionRepoStore.setHideNsfw(hide)
            _catalog.update { it.copy(hideNsfw = hide) }
        }
    }

    fun addRepo(rawUrl: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val indexUrl = ExtensionRepoStore.normalizeIndexUrl(rawUrl)
            if (indexUrl == null) {
                onResult("URL inválida: use a URL index.min.json do repositório")
                return@launch
            }
            try {
                // Validate before saving: the index must parse.
                extensionRepo.fetchIndex(indexUrl, forceRefresh = true)
                val name = extensionRepo.resolveRepoName(indexUrl)
                extensionRepoStore.addRepo(RepoEntry(name, indexUrl))
                onResult(null)
                loadCatalog()
            } catch (e: Throwable) {
                onResult(e.message ?: "Repositório inválido")
            }
        }
    }

    fun removeRepo(entry: RepoEntry) {
        viewModelScope.launch {
            extensionRepoStore.removeRepo(entry)
            if (_catalog.value.repoFilter == entry.name) {
                _catalog.update { it.copy(repoFilter = null) }
            }
            loadCatalog()
        }
    }

    fun filteredCatalog(): List<CatalogEntry> {
        val state = _catalog.value
        val query = state.query.trim().lowercase()
        return state.entries.filter { (repo, ext) ->
            (state.repoFilter == null || repo.name == state.repoFilter) &&
                (!state.hideNsfw || !ext.isNsfwEffective) &&
                (
                    query.isBlank() ||
                        ext.name.lowercase().contains(query) ||
                        ext.packageName.lowercase().contains(query) ||
                        ext.sources.any { it.name.lowercase().contains(query) }
                    )
        }
    }

    /**
     * Entry counts per repo (respecting the NSFW toggle, ignoring text query
     * and repo filter) so the UI can show what each repo actually holds.
     */
    fun repoCounts(): Map<String, Int> {
        val state = _catalog.value
        return state.entries
            .filter { !state.hideNsfw || !it.extension.isNsfwEffective }
            .groupingBy { it.repo.name }
            .eachCount()
    }

    /** Installed versionCode, or null when not installed. */
    fun installedVersionCode(packageName: String): Long? {
        return runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        }.getOrNull()
    }

    fun install(entry: RepoExtension) {
        if (installJobs[entry.packageName]?.isActive == true) return
        _installStates.update { it + (entry.packageName to InstallUiState.Downloading(null)) }
        installJobs[entry.packageName] = viewModelScope.launch {
            val result = extensionInstaller.downloadAndInstall(
                packageName = entry.packageName,
                apkUrl = entry.resources.apkUrl,
                fileName = entry.resources.apkUrl.substringAfterLast("/"),
                onProgress = { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else null
                    _installStates.update {
                        it + (entry.packageName to InstallUiState.Downloading(progress))
                    }
                },
            )
            result.onFailure { e ->
                _installStates.update {
                    it + (entry.packageName to InstallUiState.Error(e.message ?: "Erro no download"))
                }
            }.onSuccess {
                _installStates.update { it + (entry.packageName to InstallUiState.Installing) }
            }
        }
    }

    private fun refreshInstalled() {
        viewModelScope.launch {
            extensionManager.refresh()
        }
    }
}
