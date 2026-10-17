package dev.idart.mangareader.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.ui.components.MangaCover
import dev.idart.mangareader.ui.injektViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

class FeedViewModel(
    private val repository: FeedRepository,
    private val updater: FeedUpdater,
    private val exaSettings: ExaSettings,
) : ViewModel() {

    val items: Flow<List<FeedItem>> = repository.items
    val error: Flow<String?> = repository.error
    val enabled: Flow<Boolean> = repository.enabled
    val intervalHours: Flow<Long> = repository.intervalHours
    val lastRefresh: Flow<Long> = repository.lastRefresh
    val refreshing: Flow<Boolean> = repository.refreshing
    val stage: Flow<String?> = repository.stage
    val exaKey: Flow<String> = exaSettings.apiKey

    init {
        repository.schedule()
        if (repository.needsRefresh()) refresh()
    }

    fun refresh() {
        viewModelScope.launch { updater.refresh() }
    }

    fun setEnabled(value: Boolean) = repository.setEnabled(value)
    fun setIntervalHours(hours: Long) = repository.setIntervalHours(hours)
    fun setExaKey(value: String) = exaSettings.setApiKey(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onMangaOpen: (mangaId: Long) -> Unit,
    onSearchTitle: (title: String) -> Unit,
    onAiClick: () -> Unit,
) {
    val viewModel: FeedViewModel = injektViewModel { FeedViewModel(Injekt.get(), Injekt.get(), Injekt.get()) }
    val items by viewModel.items.collectAsState(initial = emptyList())
    val error by viewModel.error.collectAsState(initial = null)
    val lastRefresh by viewModel.lastRefresh.collectAsState(initial = 0L)
    val refreshing by viewModel.refreshing.collectAsState(initial = false)
    val stage by viewModel.stage.collectAsState(initial = null)
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feed") },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Atualizar agora")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configurar feed")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (lastRefresh > 0L) {
                Text(
                    "Atualizado em " + remember(lastRefresh) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(lastRefresh))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            error?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        if (message.contains("IA", ignoreCase = true)) {
                            Button(
                                onClick = onAiClick,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text("Configurar IA")
                            }
                        }
                    }
                }
            }
            if (refreshing && items.isEmpty()) {
                // Antes este caso não renderizava nada: com o feed vazio e o
                // refresh rodando, só existia o spinner minúsculo do topo — sem
                // texto algum, parecia travamento.
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stage ?: "Gerando recomendações a partir da sua biblioteca…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!refreshing && items.isEmpty() && error == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Toque em atualizar para gerar recomendações a partir da sua biblioteca.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val stageLabel = stage
                    if (refreshing && stageLabel != null) {
                        // O feed antigo continua visível; a linha diz o que está
                        // rodando, em vez de deixar o usuário adivinhando.
                        item(key = "feed-stage") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stageLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.title) },
                            supportingContent = { Text(item.reason) },
                            leadingContent = {
                                if (item.coverUrl != null) {
                                    MangaCover(
                                        coverUrl = item.coverUrl,
                                        contentDescription = item.title,
                                        modifier = Modifier.width(48.dp).aspectRatio(3f / 4f),
                                        sourceId = item.sourceId,
                                    )
                                }
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        val id = item.mangaId
                                        if (id != null) onMangaOpen(id) else onSearchTitle(item.query)
                                    },
                                ) {
                                    Icon(
                                        if (item.mangaId != null) {
                                            Icons.AutoMirrored.Filled.ArrowForward
                                        } else {
                                            Icons.Filled.Search
                                        },
                                        contentDescription = "Abrir",
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                val id = item.mangaId
                                if (id != null) onMangaOpen(id) else onSearchTitle(item.query)
                            },
                        )
                    }
                }
            }
        }
        if (showSettings) {
            FeedSettingsSheet(
                viewModel = viewModel,
                onDismiss = { showSettings = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedSettingsSheet(
    viewModel: FeedViewModel,
    onDismiss: () -> Unit,
) {
    val enabled by viewModel.enabled.collectAsState(initial = true)
    val intervalHours by viewModel.intervalHours.collectAsState(initial = FeedRepository.DEFAULT_INTERVAL_HOURS)
    val savedKey by viewModel.exaKey.collectAsState(initial = "")
    var keyDraft by remember(savedKey) { mutableStateOf(savedKey) }
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Feed", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Atualização automática")
                Switch(
                    checked = enabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                )
            }
            Text("Atualizar a cada", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedRepository.INTERVAL_OPTIONS.forEach { hours ->
                    FilterChip(
                        selected = intervalHours == hours,
                        onClick = { viewModel.setIntervalHours(hours) },
                        label = { Text(if (hours >= 24) "${hours / 24}d" else "${hours}h") },
                    )
                }
            }
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text("Chave da pesquisa Exa (opcional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        uriHandler.openUri(ExaSettings.GET_KEY_URL)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Pegar chave")
                }
                Button(
                    onClick = { viewModel.setExaKey(keyDraft) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Salvar chave")
                }
            }
            Text(
                "Com a chave, o modelo pode pesquisar novidades na web; sem ela, recomenda pelo conhecimento dele.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    viewModel.refresh()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Atualizar agora")
            }
        }
    }
}
