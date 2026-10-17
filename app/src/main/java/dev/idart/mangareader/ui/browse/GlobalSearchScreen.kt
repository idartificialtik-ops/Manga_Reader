package dev.idart.mangareader.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
    val viewModel: GlobalSearchViewModel = injektViewModel {
        GlobalSearchViewModel(
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        )
    }
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

            SearchOptionsRow(
                useAi = state.useAi,
                grouped = state.grouped,
                onlySourcesWithHits = state.onlySourcesWithHits,
                hiddenCount = state.hiddenSectionCount(),
                onUseAiChange = viewModel::setUseAi,
                onGroupedChange = viewModel::setGrouped,
                onOnlySourcesWithHitsChange = viewModel::setOnlySourcesWithHits,
            )

            if (showFilters) {
                GlobalSearchFilters(
                    state = state,
                    onToggleStatus = viewModel::toggleStatus,
                    onGenreChange = viewModel::setGenreQuery,
                    onClear = viewModel::clearFilters,
                )
            }

            state.phase?.let { phase ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = phase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when {
                    !state.searched && !state.searching -> {
                        Text(
                            "Digite e busque em todas as fontes instaladas.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    }
                    state.grouped -> GroupedResults(
                        state = state,
                        onOpen = { work ->
                            // One source only: no decision to make, open it.
                            if (work.hits.size == 1) {
                                scope.launch {
                                    val hit = work.hits.first()
                                    val id = viewModel.openManga(hit.manga, hit.sourceId)
                                    onMangaOpen(id)
                                }
                            } else {
                                viewModel.chooseSource(work)
                            }
                        },
                    )
                    else -> SectionedResults(
                        state = state,
                        onOpen = { manga, sourceId ->
                            scope.launch {
                                val id = viewModel.openManga(manga, sourceId)
                                onMangaOpen(id)
                            }
                        },
                        onSeeMore = { section ->
                            onSeeMore(section.sourceId, state.query.trim())
                        },
                        onToggleBlock = viewModel::toggleBlocked,
                    )
                }
            }
        }

        state.pendingWork?.let { pending ->
            SourceChoiceSheet(
                pending = pending,
                onPick = { choice ->
                    scope.launch {
                        val id = viewModel.openChoice(choice)
                        onMangaOpen(id)
                    }
                },
                onSearchElsewhere = viewModel::searchOtherSources,
                onDismiss = viewModel::dismissPendingWork,
            )
        }
    }
}

/** The three search behaviours, as chips, right under the field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchOptionsRow(
    useAi: Boolean,
    grouped: Boolean,
    onlySourcesWithHits: Boolean,
    hiddenCount: Int,
    onUseAiChange: (Boolean) -> Unit,
    onGroupedChange: (Boolean) -> Unit,
    onOnlySourcesWithHitsChange: (Boolean) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = useAi,
            onClick = { onUseAiChange(!useAi) },
            label = { Text("Buscar com IA") },
        )
        FilterChip(
            selected = grouped,
            onClick = { onGroupedChange(!grouped) },
            label = { Text(if (grouped) "Agrupado" else "Por fonte") },
        )
        FilterChip(
            selected = onlySourcesWithHits,
            onClick = { onOnlySourcesWithHitsChange(!onlySourcesWithHits) },
            label = { Text("Só fontes com resultado") },
        )
        if (hiddenCount > 0) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("$hiddenCount fonte(s) sem resultado oculto(s)") },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** One line per work, with the sources that also have it. */
@Composable
private fun GroupedResults(
    state: GlobalSearchUiState,
    onOpen: (FederatedSearch.MergedWork) -> Unit,
) {
    val visible = state.merged.filter { work ->
        work.hits.any { state.matches(it.manga) }
    }
    if (visible.isEmpty()) {
        EmptyResults(state)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "stats") { ResultsHeader(state) }
        items(visible, key = { work -> "w-${work.title}" }) { work ->
            val best = work.hits.first { state.matches(it.manga) }
            ListItem(
                headlineContent = { Text(work.title) },
                supportingContent = {
                    Text(sourceLabel(work, best.sourceId))
                },
                leadingContent = {
                    MangaCover(
                        coverUrl = best.manga.thumbnail_url,
                        contentDescription = work.title,
                        modifier = Modifier.width(40.dp).aspectRatio(3f / 4f),
                        sourceId = best.sourceId,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        if (work.hits.size > 1) {
                            Text(
                                text = "${work.hits.size} fontes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Abrir",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                modifier = Modifier.clickable { onOpen(work) },
            )
        }
    }
}

/** Which sources have it, and how many queries agreed. */
private fun sourceLabel(work: FederatedSearch.MergedWork, primarySourceId: Long): String {
    val others = work.hits
        .map { it.sourceId }
        .distinct()
        .filter { it != primarySourceId }
    val base = if (work.literal) {
        "achou na sua busca"
    } else {
        "achou por ${
            if (work.queryCount == 1) {
                "1 variante"
            } else {
                "${work.queryCount} variantes"
            }
        }"
    }
    if (others.isEmpty()) return base
    val more = if (others.size == 1) {
        " + 1 fonte"
    } else {
        " + ${others.size} fontes"
    }
    return "$base$more"
}

/**
 * Source picker shown when a grouped result exists in more than one source.
 *
 * The choice matters: each source is a separate database row, so picking one
 * decides which copy of the work gets tracked (chapters, updates, favourite).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceChoiceSheet(
    pending: PendingWork,
    onPick: (SourceChoice) -> Unit,
    onSearchElsewhere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "Escolher a fonte",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = pending.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = "Capítulos e traduções variam por fonte. Você favorita na que escolher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(pending.choices, key = { choice -> "choice-${choice.hit.sourceId}" }) { choice ->
                    SourceChoiceRow(choice = choice, onClick = { onPick(choice) })
                }
                if (pending.searchingElsewhere) {
                    item(key = "elsewhere-running") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "procurando este título nas outras fontes…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (pending.searchedElsewhere) {
                    item(key = "elsewhere-done") {
                        Text(
                            text = pending.elsewhereMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    item(key = "elsewhere-action") {
                        TextButton(
                            onClick = onSearchElsewhere,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Icon(Icons.Filled.TravelExplore, contentDescription = null)
                            Text(
                                text = "  Procurar em outras fontes",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChoiceRow(choice: SourceChoice, onClick: () -> Unit) {
    val manga = choice.hit.manga
    ListItem(
        headlineContent = { Text(choice.sourceName) },
        supportingContent = {
            Text(
                text = buildString {
                    append(statusLabel(manga.status))
                    manga.genre?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    if (choice.hit.literal) append(" · achou na sua busca")
                    if (choice.discovered) append(" · achou agora")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            MangaCover(
                coverUrl = manga.thumbnail_url,
                contentDescription = manga.title,
                modifier = Modifier.width(40.dp).aspectRatio(3f / 4f),
                sourceId = choice.hit.sourceId,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                if (choice.recommended) {
                    Text(
                        text = "recomendada",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (choice.libraryMangaId != null) {
                    Text(
                        text = "na sua biblioteca",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun statusLabel(status: Int): String =
    STATUS_OPTIONS.firstOrNull { option -> option.first == status }?.second
        ?: "status desconhecido"

/** Per-source blocks, the previous layout, still available. */
@Composable
private fun SectionedResults(
    state: GlobalSearchUiState,
    onOpen: (SManga, Long) -> Unit,
    onSeeMore: (FederatedSearch.SourceOutcome) -> Unit,
    onToggleBlock: (Long) -> Unit,
) {
    val sections = state.visibleSections()
    if (sections.none { it.hits.any { state.matches(it.manga) } }) {
        EmptyResults(state)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "stats") { ResultsHeader(state) }
        sections.forEach { section ->
            val visible = section.hits.filter { state.matches(it.manga) }
            item(key = "header-${section.sourceId}") {
                SectionHeader(
                    section = section,
                    hitCount = visible.size,
                    onSeeMore = { onSeeMore(section) },
                    onToggleBlock = { onToggleBlock(section.sourceId) },
                )
            }
            if (visible.isEmpty()) {
                item(key = "hidden-${section.sourceId}") {
                    Text(
                        "Nada com esses filtros",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(visible, key = { "hit-${section.sourceId}-${it.manga.url}" }) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.manga.title) },
                        leadingContent = {
                            MangaCover(
                                coverUrl = hit.manga.thumbnail_url,
                                contentDescription = hit.manga.title,
                                modifier = Modifier.width(40.dp).aspectRatio(3f / 4f),
                                sourceId = section.sourceId,
                            )
                        },
                        modifier = Modifier.clickable { onOpen(hit.manga, section.sourceId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsHeader(state: GlobalSearchUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        state.stats?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.variants.size > 1) {
            Text(
                text = "termos: " + state.variants.joinToString(" · ") { variant ->
                    "${variant.query} (${variant.label})"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    section: FederatedSearch.SourceOutcome,
    hitCount: Int,
    onSeeMore: () -> Unit,
    onToggleBlock: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(section.sourceName, style = MaterialTheme.typography.titleSmall)
            val detail = when {
                section.skipReason != null -> section.skipReason
                section.error != null -> "erro: ${section.error}"
                else -> "$hitCount resultado(s)"
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (section.foundSomething) {
            TextButton(onClick = onSeeMore) { Text("Abrir fonte") }
        }
        IconButton(onClick = onToggleBlock) {
            Icon(
                Icons.Filled.Block,
                contentDescription = "Não usar esta fonte",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
private fun EmptyResults(state: GlobalSearchUiState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = when {
                state.searching -> "Procurando…"
                state.statusFilter.isNotEmpty() || state.genreQuery.isNotBlank() ->
                    "Tudo foi ocultado pelos filtros. Limpe-os para ver os resultados."
                state.variants.isNotEmpty() ->
                    "Nada encontrado com ${state.variants.size} termo(s) de busca. " +
                        "Tente outro título ou desative \"Buscar com IA\"."
                else -> "Nada encontrado."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}
