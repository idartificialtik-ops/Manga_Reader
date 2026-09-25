package dev.idart.mangareader.ui.browse

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import dev.idart.mangareader.source.CatalogueSource
import dev.idart.mangareader.source.model.Filter
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.sources.extension.ExtensionSource
import dev.idart.mangareader.ui.components.MangaCover
import dev.idart.mangareader.ui.injektViewModel
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onSourceClick: (sourceId: Long) -> Unit,
    onSearchClick: () -> Unit,
) {
    val viewModel: BrowseViewModel = injektViewModel { BrowseViewModel(Injekt.get(), Injekt.get()) }
    val state by viewModel.state.collectAsState()
    val hiddenLangs by viewModel.hiddenLangs.collectAsState()
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    var showLangDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navegar") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Filled.Search, contentDescription = "Buscar em todas")
                    }
                },
            )
        },
    ) { padding ->
        val visibleCount = state.groups.count { it.lang !in hiddenLangs }
        if (showLangDialog) {
            val allLangs = state.groups.map { it.lang }
            AlertDialog(
                onDismissRequest = { showLangDialog = false },
                title = { Text("Idiomas") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        state.groups.forEach { group ->
                            val checked = group.lang !in hiddenLangs
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setLangVisible(group.lang, !checked) },
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { viewModel.setLangVisible(group.lang, it) },
                                )
                                Text("${group.displayName} (${group.sources.size})")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLangDialog = false }) {
                        Text("Fechar")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { viewModel.selectAllLangs() }) {
                            Text("Todos")
                        }
                        TextButton(onClick = { viewModel.deselectAllLangs(allLangs) }) {
                            Text("Nenhum")
                        }
                    }
                },
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item(key = "lang-filter") {
                ListItem(
                    headlineContent = { Text("Idiomas") },
                    supportingContent = {
                        Text("$visibleCount de ${state.groups.size} visíveis")
                    },
                    leadingContent = {
                        IconButton(onClick = { showLangDialog = true }) {
                            Icon(Icons.Filled.FilterList, contentDescription = "Filtrar idiomas")
                        }
                    },
                    modifier = Modifier.clickable { showLangDialog = true },
                )
            }
            item(key = "lang-divider") {
                HorizontalDivider()
            }
            state.groups.filter { it.lang !in hiddenLangs }.forEach { group ->
                item(key = "group-${group.lang}") {
                    val expanded = group.lang in expandedGroups
                    Column {
                        ListItem(
                            headlineContent = { Text(group.displayName) },
                            supportingContent = {
                                Text("${group.sources.size} fonte(s)")
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.toggleGroup(group.lang) }) {
                                    Icon(
                                        if (expanded) {
                                            Icons.Filled.KeyboardArrowUp
                                        } else {
                                            Icons.Filled.KeyboardArrowDown
                                        },
                                        contentDescription = if (expanded) "Recolher" else "Expandir",
                                    )
                                }
                            },
                            modifier = Modifier.clickable { viewModel.toggleGroup(group.lang) },
                        )
                        if (expanded) {
                            group.sources.forEach { source ->
                                ListItem(
                                    headlineContent = { Text(source.name) },
                                    leadingContent = { SourceIcon(source) },
                                    modifier = Modifier
                                        .clickable { onSourceClick(source.id) }
                                        .padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Extension APK icon when available, otherwise a letter avatar.
 */
@Composable
private fun SourceIcon(source: CatalogueSource) {
    val context = LocalContext.current
    val drawable = remember(source) {
        (source as? ExtensionSource)?.let {
            runCatching {
                context.packageManager.getApplicationIcon(it.extensionPackage)
            }.getOrNull()
        }
    }
    if (drawable != null) {
        Image(
            painter = rememberAsyncImagePainter(drawable),
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = source.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    sourceId: Long,
    initialQuery: String = "",
    onBack: () -> Unit,
    onMangaOpen: (mangaId: Long) -> Unit,
) {
    val viewModel: SourceBrowseViewModel = injektViewModel {
        SourceBrowseViewModel(Injekt.get(), Injekt.get(), Injekt.get(), sourceId, initialQuery)
    }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showFilters by remember { mutableStateOf(false) }
    val availableFilters by viewModel.availableFilters.collectAsState()
    val filtersTick by viewModel.filtersVersion.collectAsState()
    val filtersActive = remember(filtersTick, availableFilters) { viewModel.hasActiveFilters() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.sourceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
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
                label = { Text("Buscar") },
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
            TabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(
                    selected = state.tab == CatalogueTab.POPULAR,
                    onClick = { viewModel.setTab(CatalogueTab.POPULAR) },
                    text = { Text("Populares") },
                )
                if (state.supportsLatest) {
                    Tab(
                        selected = state.tab == CatalogueTab.LATEST,
                        onClick = { viewModel.setTab(CatalogueTab.LATEST) },
                        text = { Text("Recentes") },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.error != null && state.items.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Erro: ${state.error}")
                        Button(onClick = { viewModel.reload() }) { Text("Tentar de novo") }
                    }
                    state.items.isEmpty() -> Text(
                        "Nada encontrado",
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.url }) { manga ->
                            ListItem(
                                headlineContent = { Text(manga.title) },
                                leadingContent = {
                                    MangaCover(
                                        coverUrl = manga.thumbnail_url,
                                        contentDescription = manga.title,
                                        modifier = Modifier.width(48.dp).aspectRatio(3f / 4f),
                                        sourceId = sourceId,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        val id = viewModel.openManga(manga)
                                        onMangaOpen(id)
                                    }
                                },
                            )
                        }
                        if (state.hasNextPage) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    if (state.isAppending) {
                                        CircularProgressIndicator()
                                    } else {
                                        Button(onClick = { viewModel.loadMore() }) {
                                            Text("Carregar mais")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.isAppending) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
            if (showFilters) {
                SourceFiltersSheet(
                    filters = availableFilters,
                    tick = filtersTick,
                    onReset = { viewModel.resetFilters() },
                    onApply = {
                        viewModel.applyFilters()
                        showFilters = false
                    },
                    onDismiss = { showFilters = false },
                )
            }
        }
    }
}

/**
 * Generic sheet rendering a source's native [FilterList] (status, genres,
 * ...). Edits mutate the list in place; [onApply] reloads the catalogue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceFiltersSheet(
    filters: FilterList,
    tick: Int,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    // tick re-reads mutated filter state after reset.
    key(tick) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text("Filtros", style = MaterialTheme.typography.titleMedium)
                }
                if (filters.isEmpty()) {
                    item {
                        Text(
                            "Esta fonte não oferece filtros.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                } else {
                    itemsIndexed(filters, key = { index, filter ->
                        "$index-${filter.name}-${filter.javaClass.simpleName}"
                    }) { _, filter ->
                        FilterRow(filter)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = onReset,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Limpar")
                        }
                        Button(
                            onClick = onApply,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Aplicar")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(filter: Filter<*>) {
    when (filter) {
        is Filter.Header -> Text(
            filter.name,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        is Filter.Separator -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        is Filter.Select<*> -> SelectRow(filter)
        is Filter.Text -> {
            var value by remember(filter) { mutableStateOf(filter.state) }
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    filter.state = it
                },
                label = { Text(filter.name) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (filter.name.equals("Tags", ignoreCase = true)) {
                Text(
                    "Separadas por vírgula. Toque para adicionar:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                QuickTagsRow(
                    current = value,
                    onAdd = {
                        value = it
                        filter.state = it
                    },
                )
            }
        }
        is Filter.CheckBox -> {
            var checked by remember(filter) { mutableStateOf(filter.state) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        checked = !checked
                        filter.state = checked
                    },
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        filter.state = it
                    },
                )
                Text(filter.name)
            }
        }
        is Filter.TriState -> {
            var state by remember(filter) { mutableStateOf(filter.state) }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(filter.name, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state == Filter.TriState.STATE_IGNORE,
                        onClick = {
                            state = Filter.TriState.STATE_IGNORE
                            filter.state = state
                        },
                        label = { Text("Tanto faz") },
                    )
                    FilterChip(
                        selected = state == Filter.TriState.STATE_INCLUDE,
                        onClick = {
                            state = Filter.TriState.STATE_INCLUDE
                            filter.state = state
                        },
                        label = { Text("Incluir") },
                    )
                    FilterChip(
                        selected = state == Filter.TriState.STATE_EXCLUDE,
                        onClick = {
                            state = Filter.TriState.STATE_EXCLUDE
                            filter.state = state
                        },
                        label = { Text("Excluir") },
                    )
                }
            }
        }
        is Filter.Group<*> -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    filter.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                filter.state.forEach { child ->
                    (child as? Filter<*>)?.let { FilterRow(it) }
                }
            }
        }
        is Filter.Sort -> SortRow(filter)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectRow(filter: Filter.Select<*>) {
    var open by remember { mutableStateOf(false) }
    var selected by remember(filter) { mutableStateOf(filter.state) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = filter.values.getOrNull(selected)?.toString() ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(filter.name) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            filter.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(value.toString()) },
                    onClick = {
                        selected = index
                        filter.state = index
                        open = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SortRow(filter: Filter.Sort) {
    var open by remember { mutableStateOf(false) }
    var selected by remember(filter) { mutableStateOf(filter.state?.index ?: 0) }
    var ascending by remember(filter) { mutableStateOf(filter.state?.ascending != false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = filter.values.getOrNull(selected) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(filter.name) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            filter.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        selected = index
                        filter.state = Filter.Sort.Selection(index, ascending)
                        open = false
                    },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = !ascending,
            onCheckedChange = {
                ascending = !it
                val current = filter.state
                filter.state = Filter.Sort.Selection(current?.index ?: selected, ascending)
            },
        )
        Text("Ordem invertida")
    }
}

private val QUICK_TAGS = listOf(
    "fantasy",
    "adventure",
    "romance",
    "comedy",
    "drama",
    "sci-fi",
    "horror",
    "mystery",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickTagsRow(
    current: String,
    onAdd: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QUICK_TAGS.forEach { tag ->
            val present = current.split(",").any { it.trim().equals(tag, ignoreCase = true) }
            FilterChip(
                selected = present,
                onClick = {
                    val parts = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (present) {
                        parts.removeAll { it.equals(tag, ignoreCase = true) }
                    } else {
                        parts.add(tag)
                    }
                    onAdd(parts.joinToString(", "))
                },
                label = { Text(tag) },
            )
        }
    }
}
