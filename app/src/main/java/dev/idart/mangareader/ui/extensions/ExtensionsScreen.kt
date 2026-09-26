package dev.idart.mangareader.ui.extensions

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.SubcomposeAsyncImage
import dev.idart.mangareader.sources.extension.ExtensionManager
import dev.idart.mangareader.sources.extension.repo.RepoExtension
import dev.idart.mangareader.ui.browse.langDisplayName
import dev.idart.mangareader.ui.injektViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onBack: () -> Unit,
    onSourceConfig: (sourceId: Long) -> Unit = {},
    onSourceWebView: (sourceId: Long) -> Unit = {},
) {
    val viewModel: ExtensionsViewModel = injektViewModel {
        ExtensionsViewModel(
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get<Application>().packageManager,
        )
    }
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Volta do diálogo de desinstalação do sistema: a lista precisa mudar.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Resultado da desinstalação (sucesso, recusa, etc.).
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Extensões") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Verificar de novo")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Instaladas") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Catálogo") },
                )
            }
            when (tab) {
                0 -> InstalledTab(
                    state = state,
                    onUninstall = { pkg ->
                        // Desinstalação passa pelo diálogo do sistema: o
                        // PackageInstaller sem consentimento do usuário está
                        // hidden a partir da API 30.
                        viewModel.notifyOnce("Confirme a remoção na tela do sistema.")
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            viewModel.notifyOnce(
                                "Não consegui abrir a tela do sistema. Remova em " +
                                    "Ajustes › Aplicativos.",
                            )
                        }
                    },
                    onSourceConfig = onSourceConfig,
                    onSourceWebView = onSourceWebView,
                )
                else -> CatalogTab(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun InstalledTab(
    state: ExtensionsUiState,
    onUninstall: (packageName: String) -> Unit,
    onSourceConfig: (sourceId: Long) -> Unit,
    onSourceWebView: (sourceId: Long) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading && state.extensions.isEmpty() -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.extensions.isEmpty() -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Nenhuma extensão encontrada", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Busque e instale fontes na aba Catálogo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.extensions, key = { it.packageName }) { ext ->
                        ExtensionRow(
                            ext = ext,
                            onUninstall = { onUninstall(ext.packageName) },
                            onSourceConfig = onSourceConfig,
                            onSourceWebView = onSourceWebView,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(
    ext: ExtensionManager.ExtensionInfo,
    onUninstall: () -> Unit,
    onSourceConfig: (sourceId: Long) -> Unit,
    onSourceWebView: (sourceId: Long) -> Unit,
) {
    var expanded by remember(ext.packageName) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    // Extensão Keiyoushi pode empacotar dezenas de fontes, cada uma com suas
    // próprias configurações: o menu precisa deixá-lo escolher qual.
    var pickerOpen by remember { mutableStateOf(false) }
    val configurable = ext.sources.filter { it.isConfigurable }

    Column {
        ListItem(
            headlineContent = { Text(ext.label) },
            supportingContent = {
                Text(
                    buildString {
                        append("v${ext.versionName}")
                        append(" • ${ext.sources.size} fonte(s)")
                        if (ext.error != null) append("\nErro: ${ext.error}")
                    },
                )
            },
            leadingContent = {
                val bitmap = remember(ext.packageName) {
                    runCatching { ext.icon?.toBitmap()?.asImageBitmap() }.getOrNull()
                }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(40.dp))
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Opções da extensão")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Configurações…") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                enabled = configurable.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    // Uma fonte só: vai direto. Várias: escolhe.
                                    if (configurable.size == 1) {
                                        onSourceConfig(configurable.first().id)
                                    } else {
                                        pickerOpen = true
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Desinstalar") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    confirmUninstall = true
                                },
                            )
                        }
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Recolher" else "Ver fontes",
                        )
                    }
                }
            },
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            ext.sources.forEach { source ->
                ListItem(
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(source.lang) },
                    trailingContent = {
                        Row {
                            if (source.isConfigurable) {
                                IconButton(onClick = { onSourceConfig(source.id) }) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = "Configurar ${source.name}",
                                    )
                                }
                            }
                            if (source.baseUrl != null) {
                                IconButton(onClick = { onSourceWebView(source.id) }) {
                                    Icon(
                                        Icons.Filled.Language,
                                        contentDescription = "WebView de ${source.name}",
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 56.dp),
                )
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Desinstalar extensão?") },
            text = {
                Text(
                    "${ext.label} e suas ${ext.sources.size} fonte(s) serão removidas, " +
                        "junto com as configurações salvas nelas.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = false
                        onUninstall()
                    },
                ) {
                    Text("Desinstalar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("Cancelar") }
            },
        )
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("Configurar qual fonte?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    configurable.forEach { source ->
                        ListItem(
                            headlineContent = { Text(source.name) },
                            supportingContent = { Text(source.lang) },
                            modifier = Modifier.clickable {
                                pickerOpen = false
                                onSourceConfig(source.id)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun CatalogTab(viewModel: ExtensionsViewModel) {
    val catalog by viewModel.catalog.collectAsState()
    val installStates by viewModel.installStates.collectAsState()
    var showAddRepo by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = catalog.query,
            onValueChange = viewModel::setQuery,
            label = { Text("Buscar extensão ou fonte") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val counts = viewModel.repoCounts()
            val total = counts.values.sum()
            FilterChip(
                selected = catalog.repoFilter == null,
                onClick = { viewModel.setRepoFilter(null) },
                label = { Text("Todos ($total)") },
            )
            catalog.repos.forEach { repo ->
                FilterChip(
                    selected = catalog.repoFilter == repo.name,
                    onClick = {
                        viewModel.setRepoFilter(if (catalog.repoFilter == repo.name) null else repo.name)
                    },
                    label = { Text("${repo.name} (${counts[repo.name] ?: 0})") },
                )
            }
            IconButton(onClick = { showAddRepo = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Adicionar repositório")
            }
            if (catalog.repoFilter != null) {
                IconButton(onClick = {
                    catalog.repos.firstOrNull { it.name == catalog.repoFilter }?.let {
                        viewModel.removeRepo(it)
                    }
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remover repositório")
                }
            }
        }
        val langs = viewModel.availableLangs()
        if (langs.size > 1) {
            val langCounts = viewModel.langCounts()
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "lang-all") {
                    FilterChip(
                        selected = catalog.langFilter == null,
                        onClick = { viewModel.setLangFilter(null) },
                        label = { Text("Todos os idiomas (${viewModel.visibleExtensionCount()})") },
                    )
                }
                items(langs, key = { "lang-$it" }) { lang ->
                    FilterChip(
                        selected = catalog.langFilter == lang,
                        onClick = { viewModel.setLangFilter(lang) },
                        label = { Text("${langDisplayName(lang)} (${langCounts[lang] ?: 0})") },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Ocultar 18+", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = catalog.hideNsfw, onCheckedChange = viewModel::setHideNsfw)
        }
        if (catalog.repoWarnings.isNotEmpty()) {
            Text(
                catalog.repoWarnings.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when {
                catalog.isLoading && catalog.entries.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                catalog.error != null && catalog.entries.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Erro: ${catalog.error}")
                        Button(onClick = { viewModel.loadCatalog(forceRefresh = true) }) {
                            Text("Tentar de novo")
                        }
                    }
                }
                else -> {
                    val entries = viewModel.filteredCatalog()
                    if (entries.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (catalog.repoFilter != null) {
                                    "O repositório ${catalog.repoFilter} não tem extensões " +
                                        "— pode ter sido descontinuado (como o Yozono, migrado para o Keiyoushi)."
                                } else {
                                    "Nada encontrado"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(entries, key = { "${it.repo.name}-${it.extension.packageName}" }) { item ->
                                CatalogRow(
                                    repoName = item.repo.name,
                                    entry = item.extension,
                                    installState = installStates[item.extension.packageName]
                                        ?: InstallUiState.Idle,
                                    installedCode = viewModel.installedVersionCode(item.extension.packageName),
                                    onInstall = { viewModel.install(item.extension) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRepo) {
        var url by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showAddRepo = false },
            title = { Text("Adicionar repositório") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Cole a URL index.min.json do repositório.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            error = null
                        },
                        label = { Text("URL do repositório") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {}),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        busy = true
                        viewModel.addRepo(url) { failure ->
                            busy = false
                            if (failure == null) {
                                showAddRepo = false
                            } else {
                                error = failure
                            }
                        }
                    },
                ) { Text("Adicionar") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showAddRepo = false }) { Text("Voltar") }
            },
        )
    }
}

@Composable
private fun CatalogRow(
    repoName: String,
    entry: RepoExtension,
    installState: InstallUiState,
    installedCode: Long?,
    onInstall: () -> Unit,
) {
    val isInstalled = installedCode != null
    val hasUpdate = isInstalled && installedCode < entry.versionCodeLong
    var expanded by remember(entry.packageName) { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry.name)
                    if (entry.isNsfwEffective) {
                        Text("🔞", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            supportingContent = {
                Column {
                    val langs = entry.sources.map { it.effectiveLang }.distinct().take(3).joinToString(", ")
                    Text(
                        buildString {
                            append(repoName)
                            append(" • v${entry.versionName}")
                            if (langs.isNotBlank()) append(" • $langs")
                            append(" • ${entry.sources.size} fonte(s)")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when (installState) {
                        is InstallUiState.Downloading -> {
                            if (installState.progress != null) {
                                LinearProgressIndicator(
                                    progress = { installState.progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                                Text(
                                    "Baixando ${(installState.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text("Baixando…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is InstallUiState.Installing -> {
                            Text("Aguardando confirmação…", style = MaterialTheme.typography.bodySmall)
                        }
                        is InstallUiState.Error -> {
                            Text(
                                "Erro: ${installState.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        InstallUiState.Idle -> {}
                    }
                }
            },
            leadingContent = {
                SubcomposeAsyncImage(
                    model = entry.resources.iconUrl.ifBlank { null },
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    error = {
                        Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(40.dp))
                    },
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        installState is InstallUiState.Downloading ||
                            installState is InstallUiState.Installing -> {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        isInstalled && !hasUpdate && installState is InstallUiState.Idle -> {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Instalado",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        else -> {
                            if (installState is InstallUiState.Error) {
                                TextButton(onClick = onInstall) { Text("Repetir") }
                            } else {
                                Button(onClick = onInstall) {
                                    Text(if (hasUpdate) "Atualizar" else "Instalar")
                                }
                            }
                        }
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Recolher" else "Ver idiomas",
                        )
                    }
                }
            },
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            entry.sources.forEach { source ->
                ListItem(
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(source.effectiveLang) },
                    modifier = Modifier.padding(start = 56.dp),
                )
            }
        }
    }
}
