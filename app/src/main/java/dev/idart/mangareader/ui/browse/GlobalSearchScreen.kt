package dev.idart.mangareader.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.idart.mangareader.source.model.SManga
import dev.idart.mangareader.ui.components.MangaCover
import dev.idart.mangareader.ui.injektViewModel
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    initialQuery: String = "",
    onBack: () -> Unit,
    onMangaOpen: (mangaId: Long) -> Unit,
    onSeeMore: (sourceId: Long, query: String) -> Unit,
) {
    val viewModel: GlobalSearchViewModel = injektViewModel { GlobalSearchViewModel(Injekt.get(), Injekt.get(), Injekt.get()) }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showFilters by remember { mutableStateOf(false) }
    val filtersActive = state.statusFilter.isNotEmpty() || state.genreQuery.isNotBlank()

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            viewModel.setQuery(initialQuery)
            viewModel.search()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Buscar em todas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = "Filtros",
                            tint = if (filtersActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Buscar mangá") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                trailingIcon = {
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Buscar")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (showFilters) {
                GlobalSearchFilters(
                    state = state,
                    onToggleStatus = viewModel::toggleStatus,
                    onGenreChange = viewModel::setGenreQuery,
                    onClear = viewModel::clearFilters,
                )
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                if (!state.searched) {
                    Text(
                        "Digite e busque em todas as fontes instaladas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        state.sections.forEach { section ->
                            item(key = "header-${section.sourceId}") {
                                SectionHeader(
                                    section = section,
                                    onSeeMore = { onSeeMore(section.sourceId, state.query.trim()) },
                                )
                            }
                            when (val result = section.result) {
                                is SourceSearchResult.Loading -> {
                                    item(key = "loading-${section.sourceId}") {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }
                                is SourceSearchResult.Error -> {
                                    item(key = "error-${section.sourceId}") {
                                        Text(
                                            "Erro: ${result.message}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                                is SourceSearchResult.Content -> {
                                    val visible = result.items.filter { state.matches(it) }
                                    if (visible.isEmpty()) {
                                        item(key = "empty-${section.sourceId}") {
                                            Text(
                                                if (result.items.isEmpty()) {
                                                    "Nada encontrado"
                                                } else {
                                                    "Ocultos pelos filtros"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                            )
                                        }
                                    } else {
                                        items(
                                            visible.take(GlobalSearchViewModel.MAX_VISIBLE_PER_SOURCE),
                                            key = { "g-${section.sourceId}-${it.url}" },
                                        ) { manga ->
                                            ListItem(
                                                headlineContent = { Text(manga.title) },
                                                leadingContent = {
                                                    MangaCover(
                                                        coverUrl = manga.thumbnail_url,
                                                        contentDescription = manga.title,
                                                        modifier = Modifier.width(40.dp).aspectRatio(3f / 4f),
                                                        sourceId = section.sourceId,
                                                    )
                                                },
                                                modifier = Modifier.clickable {
                                                    scope.launch {
                                                        val id = viewModel.openManga(manga, section.sourceId)
                                                        onMangaOpen(id)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    section: SourceSearchSection,
    onSeeMore: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            section.sourceName,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        val result = section.result
        if (result is SourceSearchResult.Content && result.items.size > GlobalSearchViewModel.MAX_VISIBLE_PER_SOURCE) {
            TextButton(
                onClick = onSeeMore,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text("Ver mais")
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else if (result is SourceSearchResult.Content && result.items.isNotEmpty()) {
            TextButton(
                onClick = onSeeMore,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text("Abrir fonte")
            }
        }
    }
}

private val STATUS_OPTIONS = listOf(
    SManga.ONGOING to "Em andamento",
    SManga.COMPLETED to "Concluído",
    SManga.ON_HIATUS to "Hiato",
    SManga.CANCELLED to "Cancelado",
    SManga.PUBLISHING_FINISHED to "Finalizado",
    SManga.LICENSED to "Licenciado",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GlobalSearchFilters(
    state: GlobalSearchUiState,
    onToggleStatus: (Int) -> Unit,
    onGenreChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            STATUS_OPTIONS.forEach { (code, label) ->
                FilterChip(
                    selected = code in state.statusFilter,
                    onClick = { onToggleStatus(code) },
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = state.genreQuery,
            onValueChange = onGenreChange,
            label = { Text("Gênero (ex.: romance, ação)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (state.statusFilter.isNotEmpty() || state.genreQuery.isNotBlank()) {
            TextButton(onClick = onClear) {
                Text("Limpar filtros")
            }
        }
        Text(
            "Filtros ocultam resultados sem esses dados.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
