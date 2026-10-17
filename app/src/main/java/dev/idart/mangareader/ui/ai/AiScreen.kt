package dev.idart.mangareader.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.idart.mangareader.ui.components.MangaCover
import dev.idart.mangareader.ui.components.MarkdownText
import dev.idart.mangareader.ui.injektViewModel
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    onBack: () -> Unit,
    onMangaOpen: (mangaId: Long) -> Unit,
) {
    val viewModel: AiViewModel = injektViewModel {
        AiViewModel(Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get())
    }
    val messages by viewModel.messages.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val activity by viewModel.activity.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val pendingRemoval by viewModel.pendingExtensionRemoval.collectAsState()
    val threads by viewModel.threads.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistente IA") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    // Conversa atual fica salva ao trocar: o botão é "nova",
                    // não "apagar" — apagar vive no histórico.
                    IconButton(onClick = { viewModel.newThread() }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Nova conversa")
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Filled.History, contentDescription = "Histórico de conversas")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configurar IA")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Pergunte por mangás (\"isekai com mais de 100 capítulos\", \"recomende um romance\") — busco nas suas fontes instaladas com a sua chave.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(messages.size, key = { it }) { index ->
                    ChatBubble(
                        msg = messages[index],
                        onMangaOpen = { hit ->
                            scope.launch {
                                val id = viewModel.openHit(hit)
                                onMangaOpen(id)
                            }
                        },
                    )
                }
                if (busy) {
                    item {
                        // Spinner sozinho não diz nada: o usuário achava que o
                        // app tinha travado.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = activity ?: "pensando…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (notice != null) {
                    item {
                        // Fica depois que a atividade acaba: sumir sem
                        // explicação não diz nada sobre o resultado.
                        Text(
                            text = notice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Pergunte…") },
                    singleLine = false,
                    maxLines = 4,
                    // Padding interno: sem isso o placeholder/texto fica
                    // cortado no topo quando a caixa cresce (multiline).
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.35f,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            viewModel.send(input)
                            input = ""
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = !busy && input.isNotBlank(),
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                }
            }
        }
    }

    if (showSettings) {
        AiSettingsSheet(
            viewModel = viewModel,
            onDismiss = { showSettings = false },
        )
    }

    if (showHistory) {
        ChatHistorySheet(
            threads = threads,
            onOpen = { id ->
                showHistory = false
                viewModel.openThread(id)
            },
            onDelete = viewModel::deleteThread,
            onClearAll = {
                showHistory = false
                viewModel.clearHistory()
            },
            onDismiss = { showHistory = false },
        )
    }

    // O assistente pediu para remover uma extensão: o usuário decide.
    pendingRemoval?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmExtensionRemoval(false) },
            title = { Text("Remover extensão?") },
            text = {
                Text(
                    "O assistente quer desinstalar “${pending.label}”. " +
                        "A extensão e as configurações salvas nas fontes dela serão apagadas.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmExtensionRemoval(true) }) {
                    Text("Remover", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmExtensionRemoval(false) }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
private fun ChatBubble(
    msg: UiChatMessage,
    onMangaOpen: (MangaHit) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.fromUser) Alignment.End else Alignment.Start,
    ) {
        Card(
            // Par container/texto declarado de propósito: depender da resolução
            // automática deixava a resposta do assistente escura demais para ler
            // no tema tingido pela capa.
            colors = when {
                msg.fromUser -> CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                msg.isError -> CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                else -> CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            if (msg.fromUser || msg.isError) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                MarkdownText(
                    msg.text,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        msg.hits.forEach { hit ->
            ListItem(
                headlineContent = { Text(hit.title) },
                supportingContent = { Text(hit.sourceName) },
                leadingContent = {
                    MangaCover(
                        coverUrl = hit.thumbnailUrl,
                        contentDescription = hit.title,
                        modifier = Modifier.width(40.dp).aspectRatio(3f / 4f),
                        sourceId = hit.sourceId,
                    )
                },
                trailingContent = {
                    Icon(Icons.Filled.Add, contentDescription = "Abrir")
                },
                modifier = Modifier.fillMaxWidth(0.92f).clickable { onMangaOpen(hit) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHistorySheet(
    threads: List<ChatThread>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Histórico",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (threads.isNotEmpty()) {
                    TextButton(onClick = onClearAll) { Text("Limpar tudo") }
                }
            }
            if (threads.isEmpty()) {
                Text(
                    "Nenhuma conversa salva ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(threads, key = { it.id }) { thread ->
                        ListItem(
                            headlineContent = { Text(thread.title, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    "${thread.messages.size} mensagens • " +
                                        remember(thread.updatedAt) {
                                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                                .format(Date(thread.updatedAt))
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onDelete(thread.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Apagar conversa")
                                }
                            },
                            modifier = Modifier.clickable { onOpen(thread.id) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsSheet(
    viewModel: AiViewModel,
    onDismiss: () -> Unit,
) {
    val baseUrl by viewModel.baseUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val model by viewModel.model.collectAsState()
    val models by viewModel.models.collectAsState()
    val modelsLoading by viewModel.modelsLoading.collectAsState()
    val modelsError by viewModel.modelsError.collectAsState()
    val streamEnabled by viewModel.streamEnabled.collectAsState()
    var urlDraft by remember(baseUrl) { mutableStateOf(baseUrl) }
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember(model) { mutableStateOf(model) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Provedor de IA", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AiSettings.PRESETS.take(3).forEach { preset ->
                    FilterChip(
                        selected = urlDraft.trimEnd('/') == preset.baseUrl,
                        onClick = {
                            urlDraft = preset.baseUrl
                            modelDraft = preset.model
                        },
                        label = { Text(preset.name) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiSettings.PRESETS.drop(3).forEach { preset ->
                    FilterChip(
                        selected = urlDraft.trimEnd('/') == preset.baseUrl,
                        onClick = {
                            urlDraft = preset.baseUrl
                            modelDraft = preset.model
                        },
                        label = { Text(preset.name) },
                    )
                }
            }
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("Base URL (…/v1 ou …/chat/completions)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text(if (apiKey.isBlank()) "API key" else "API key (preenchida)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            ExposedDropdownMenuBox(
                expanded = modelMenuOpen,
                onExpandedChange = { modelMenuOpen = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it },
                    label = { Text("Modelo") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = { modelMenuOpen = false },
                ) {
                    models.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(id) },
                            onClick = {
                                modelDraft = id
                                modelMenuOpen = false
                            },
                        )
                    }
                }
            }
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.fetchModelsWithSaved(urlDraft, keyDraft) },
                enabled = !modelsLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (modelsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text("Buscar modelos do provedor")
                }
            }
            modelsError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Respostas em tempo real")
                Switch(
                    checked = streamEnabled,
                    onCheckedChange = { viewModel.setStreamEnabled(it) },
                )
            }
            androidx.compose.material3.Button(
                onClick = {
                    viewModel.setBaseUrl(urlDraft)
                    if (keyDraft.isNotBlank()) viewModel.setApiKey(keyDraft)
                    viewModel.setModel(modelDraft)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salvar")
            }
        }
    }
}
