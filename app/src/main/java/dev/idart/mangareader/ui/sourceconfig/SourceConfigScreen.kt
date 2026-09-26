package dev.idart.mangareader.ui.sourceconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.ui.injektViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Extension settings: username/password logins, cookies, API keys.
 *
 * The values live in the `source_<id>` SharedPreferences file the extension
 * itself reads, so a login filled in here is used on the next request without
 * any extra plumbing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceConfigScreen(
    sourceId: Long,
    onBack: () -> Unit,
) {
    val viewModel: SourceConfigViewModel = injektViewModel {
        SourceConfigViewModel(Injekt.get(), Injekt.get<SourceManager>(), sourceId)
    }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.sourceName.ifBlank { "Configurações da fonte" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    if (state.screen != null) {
                        IconButton(onClick = viewModel::load) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Recarregar")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = viewModel::load) { Text("Tentar de novo") }
                }
                else -> {
                    val screen = state.screen
                    if (screen == null) {
                        Text(
                            text = "Esta fonte não tem nada a configurar.",
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        PreferenceScreenList(screen = screen, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
