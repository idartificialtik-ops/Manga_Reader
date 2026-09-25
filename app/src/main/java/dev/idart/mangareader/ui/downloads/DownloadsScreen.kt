package dev.idart.mangareader.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import dev.idart.mangareader.sources.download.DownloadManager
import dev.idart.mangareader.sources.download.DownloadedGroup
import dev.idart.mangareader.ui.components.MangaCover
import dev.idart.mangareader.ui.injektViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private enum class PendingBulkAction {
    CANCEL_ALL,
    DELETE_ALL,
    DELETE_GROUP,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onChapterOpen: (mangaId: Long, chapterId: Long) -> Unit,
) {
    val viewModel: DownloadsViewModel = injektViewModel {
        DownloadsViewModel(Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get())
    }
    val rows by viewModel.rows.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingBulkAction?>(null) }
    var pendingGroupId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    IconButton(onClick = { viewModel.setWifiOnly(!wifiOnly) }) {
                        Icon(
                            if (wifiOnly) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            contentDescription = if (wifiOnly) "Somente Wi-Fi ativo" else "Baixar em qualquer rede",
                            tint = if (wifiOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Opções")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Retomar pausados") },
                            onClick = {
                                menuExpanded = false
                                viewModel.resumeAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Cancelar fila") },
                            onClick = {
                                menuExpanded = false
                                pendingAction = PendingBulkAction.CANCEL_ALL
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Apagar tudo baixado") },
                            onClick = {
                                menuExpanded = false
                                pendingAction = PendingBulkAction.DELETE_ALL
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Limpar concluídos") },
                            onClick = {
                                menuExpanded = false
                                viewModel.pruneFinished()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (rows.isEmpty() && groups.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Nenhum download", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Baixe capítulos na tela de detalhes de um mangá.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Somente Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "Armazenamento: ${DownloadManager.formatBytes(totalBytes)}" +
                                if (wifiOnly) " · Somente Wi-Fi" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (rows.isNotEmpty()) {
                        item {
                            Text(
                                "Ativos (${rows.size})",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(rows, key = { "active-${it.chapterId}" }) { row ->
                            ActiveDownloadRow(
                                row = row,
                                onPause = { viewModel.pause(row.mangaId, row.chapterId) },
                                onResume = { viewModel.resume(row.mangaId, row.chapterId) },
                                onRetry = { viewModel.retry(row.mangaId, row.chapterId) },
                                onCancel = { viewModel.cancel(row.mangaId, row.chapterId) },
                                onDelete = { viewModel.delete(row.mangaId, row.chapterId) },
                            )
                        }
                    }
                    if (groups.isNotEmpty()) {
                        item {
                            Text(
                                "Baixados (${groups.sumOf { it.chapters.size }} capítulos)",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        groups.forEach { group ->
                            item(key = "group-${group.mangaId}") {
                                var expanded by remember(group.mangaId) { mutableStateOf(false) }
                                Column {
                                    DownloadedGroupHeader(
                                        group = group,
                                        expanded = expanded,
                                        onToggle = { expanded = !expanded },
                                        onDeleteGroup = {
                                            pendingGroupId = group.mangaId
                                            pendingAction = PendingBulkAction.DELETE_GROUP
                                        },
                                    )
                                    if (expanded) {
                                        group.chapters.forEach { chapter ->
                                            ListItem(
                                                headlineContent = { Text(chapter.name) },
                                                supportingContent = {
                                                    Text(DownloadManager.formatBytes(chapter.sizeBytes))
                                                },
                                                trailingContent = {
                                                    Row {
                                                        IconButton(
                                                            onClick = {
                                                                onChapterOpen(group.mangaId, chapter.chapterId)
                                                            },
                                                        ) {
                                                            Icon(Icons.Filled.PlayArrow, contentDescription = "Ler")
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.delete(group.mangaId, chapter.chapterId)
                                                            },
                                                        ) {
                                                            Icon(Icons.Filled.Delete, contentDescription = "Apagar")
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.clickable {
                                                    onChapterOpen(group.mangaId, chapter.chapterId)
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

    when (pendingAction) {
        PendingBulkAction.CANCEL_ALL -> {
            ConfirmDialog(
                title = "Cancelar fila",
                message = "Parar todos os downloads em andamento? Os arquivos parciais são mantidos.",
                confirmLabel = "Cancelar fila",
                onDismiss = { pendingAction = null },
                onConfirm = {
                    pendingAction = null
                    viewModel.cancelAll()
                },
            )
        }
        PendingBulkAction.DELETE_ALL -> {
            ConfirmDialog(
                title = "Apagar tudo",
                message = "Apagar todos os capítulos baixados (${DownloadManager.formatBytes(totalBytes)})?",
                confirmLabel = "Apagar tudo",
                onDismiss = { pendingAction = null },
                onConfirm = {
                    pendingAction = null
                    viewModel.deleteAll()
                },
            )
        }
        PendingBulkAction.DELETE_GROUP -> {
            val group = groups.firstOrNull { it.mangaId == pendingGroupId }
            ConfirmDialog(
                title = "Apagar downloads",
                message = "Apagar ${group?.chapters?.size ?: 0} capítulos de ${group?.mangaTitle ?: "este mangá"}?",
                confirmLabel = "Apagar",
                onDismiss = {
                    pendingAction = null
                    pendingGroupId = null
                },
                onConfirm = {
                    pendingAction = null
                    pendingGroupId?.let { viewModel.deleteGroup(it) }
                    pendingGroupId = null
                },
            )
        }
        null -> {}
    }
}

@Composable
private fun ActiveDownloadRow(
    row: DownloadRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.chapterName) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.mangaTitle)
                if (row.total > 0) {
                    LinearProgressIndicator(
                        progress = { row.progress.toFloat() / row.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${row.progress}/${row.total} páginas")
                } else {
                    Text(
                        when {
                            row.paused -> "Pausado"
                            row.state == WorkInfo.State.ENQUEUED -> "Na fila"
                            row.state == WorkInfo.State.RUNNING -> "Baixando…"
                            row.state == WorkInfo.State.BLOCKED -> "Aguardando rede"
                            else -> row.state.name
                        },
                    )
                }
            }
        },
        trailingContent = {
            Row {
                when {
                    row.paused -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Continuar")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Apagar parcial")
                        }
                    }
                    row.state == WorkInfo.State.FAILED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Repetir")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Apagar parcial")
                        }
                    }
                    else -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pausar")
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancelar")
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun DownloadedGroupHeader(
    group: DownloadedGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(group.mangaTitle) },
        supportingContent = {
            Text(
                "${group.chapters.size} capítulos baixados · ${DownloadManager.formatBytes(group.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = {
            MangaCover(
                coverUrl = group.coverUrl,
                contentDescription = group.mangaTitle,
                modifier = Modifier.width(48.dp).aspectRatio(3f / 4f),
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Recolher" else "Ver capítulos",
                    )
                }
                IconButton(onClick = onDeleteGroup) {
                    Icon(Icons.Filled.Delete, contentDescription = "Apagar mangá")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onToggle),
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Voltar") }
        },
    )
}
