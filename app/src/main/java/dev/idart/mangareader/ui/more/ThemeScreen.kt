package dev.idart.mangareader.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.idart.mangareader.ui.injektViewModel
import dev.idart.mangareader.ui.theme.THEME_SEEDS
import dev.idart.mangareader.ui.theme.ThemeMode
import dev.idart.mangareader.ui.theme.contentColorFor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    onBack: () -> Unit,
) {
    val viewModel: ThemeViewModel = injektViewModel { ThemeViewModel(Injekt.get()) }
    val themeMode by viewModel.themeMode.collectAsState()
    val seedIndex by viewModel.seedIndex.collectAsState()
    val amoled by viewModel.amoled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aparência") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Modo",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ThemeOption(
                    label = "Sistema",
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                )
            }
            item {
                ThemeOption(
                    label = "Claro",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                )
            }
            item {
                ThemeOption(
                    label = "Escuro",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                )
            }
            item {
                Text(
                    "Cor",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                SeedGrid(
                    selectedIndex = seedIndex,
                    onSelect = viewModel::setSeedIndex,
                )
            }
            // Só faz sentido no escuro: usa o mesmo gate do MangaReaderTheme.
            if (themeMode != ThemeMode.LIGHT) {
                item {
                    ListItem(
                        headlineContent = { Text("Preto puro (AMOLED)") },
                        supportingContent = {
                            Text("Fundo 100% preto no modo escuro — economiza bateria")
                        },
                        trailingContent = {
                            Switch(
                                checked = amoled,
                                onCheckedChange = viewModel::setAmoled,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedGrid(selectedIndex: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SeedDot(
            name = "Sistema",
            color = null,
            selected = selectedIndex == 0,
            onClick = { onSelect(0) },
        )
        THEME_SEEDS.forEachIndexed { i, seed ->
            SeedDot(
                name = seed.name,
                color = seed.color,
                selected = selectedIndex == i + 1,
                onClick = { onSelect(i + 1) },
            )
        }
    }
}

@Composable
private fun SeedDot(name: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .clip(CircleShape)
                .then(
                    if (color != null) {
                        Modifier.background(color)
                    } else {
                        Modifier.background(
                            Brush.sweepGradient(
                                listOf(
                                    Color.Red,
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Cyan,
                                    Color.Blue,
                                    Color.Magenta,
                                    Color.Red,
                                ),
                            ),
                        )
                    },
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (color != null) contentColorFor(color) else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
}
