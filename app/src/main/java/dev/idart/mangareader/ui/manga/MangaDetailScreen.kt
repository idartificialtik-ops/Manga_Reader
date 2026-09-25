package dev.idart.mangareader.ui.manga

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.idart.mangareader.source.model.SManga
import dev.idart.mangareader.sources.download.ChapterDownloadState
import dev.idart.mangareader.ui.components.MangaCover
import dev.idart.mangareader.ui.injektViewModel
import dev.idart.mangareader.ui.theme.MangaReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    mangaId: Long,
    onBack: () -> Unit,
    onChapterClick: (chapterId: Long) -> Unit,
    onMangaClick: (mangaId: Long) -> Unit,
) {
    val viewModel: MangaDetailViewModel = injektViewModel {
        MangaDetailViewModel(
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
            mangaId,
        )
    }
    val state by viewModel.state.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Tema por capa (estilo Komikku): a cor extraída da capa regenera o
    // ColorScheme inteiro da tela — fundo, chips, botões, tudo.
    val palette by coverPalette(state.manga?.thumbnailUrl)
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    MangaReaderTheme(darkTheme = isDarkTheme, coverSeed = palette?.tint) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null && state.manga == null -> Text(
                    text = "Erro: ${state.error}",
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
                state.manga == null -> Text(
                    text = "Mangá não encontrado",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val manga = state.manga!!
                    val tintedBg = MaterialTheme.colorScheme.background
                    val onSurface = MaterialTheme.colorScheme.onSurface
                    val themePrimary = MaterialTheme.colorScheme.primary
                    val accent = remember(palette, tintedBg, onSurface, themePrimary) {
                        // Um único matiz por mangá: o accent sai do próprio tema
                        // (seed da capa). Antes vinha de `vibrant` e destoava
                        // das superfícies (botão azul em tela marrom).
                        readableAccent(themePrimary, tintedBg, onSurface)
                    }
                    Box(
                        modifier = Modifier.fillMaxSize().background(tintedBg),
                    ) {
                        PullToRefreshBox(
                            isRefreshing = state.isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                            ) {
                                state.error?.let { error ->
                                    item {
                                        Text(
                                            "Aviso: $error",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                                item {
                                    DetailHeader(
                                        title = manga.title,
                                        author = manga.author,
                                        statusLabel = statusLabel(manga.status),
                                        sourceName = state.sourceName,
                                        coverUrl = manga.thumbnailUrl,
                                        sourceId = manga.source,
                                        dominant = palette?.dominant,
                                        accent = accent,
                                        background = tintedBg,
                                        onBack = onBack,
                                        onDownloadAll = { viewModel.downloadAll() },
                                        // Ações (coração, monitorar…) vivem dentro do
                                        // header: sem emenda visível entre o scrim do
                                        // cabeçalho e o fundo da tela.
                                        actions = {
                                            DetailActions(
                                                favorite = manga.favorite,
                                                onFavoriteClick = { viewModel.toggleFavorite() },
                                                webUrl = state.mangaUrl,
                                                onWebClick = { url ->
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, url.toUri()),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                                item {
                                    ExpandableDescription(description = manga.description, accent = accent)
                                }
                                if (!manga.genre.isNullOrEmpty()) {
                                    item {
                                        GenreChips(genres = manga.genre!!)
                                    }
                                }
                                if (state.chapters.isNotEmpty()) {
                                    item {
                                        // Display order is newest-first; reading order is oldest-first.
                                        val readingOrder = state.chapters.asReversed()
                                        val firstUnread = readingOrder.firstOrNull { !it.read }
                                        val (label, target) = when {
                                            firstUnread == null -> "Ler novamente" to readingOrder.first()
                                            firstUnread == readingOrder.first() -> "Começar a ler" to firstUnread
                                            else -> "Continuar: ${firstUnread.name}" to firstUnread
                                        }
                                        OutlinedButton(
                                            onClick = { onChapterClick(target.id) },
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                accent ?: MaterialTheme.colorScheme.outline,
                                            ),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = accent ?: MaterialTheme.colorScheme.onSurface,
                                            ),
                                        ) {
                                            Text(
                                                label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                }
                                when (val sug = suggestions) {
                                    is SuggestionsUiState.Loading -> {
                                        item {
                                            HorizontalDivider()
                                        }
                                        item {
                                            SuggestionsLoading()
                                        }
                                    }
                                    is SuggestionsUiState.Content -> {
                                        item {
                                            HorizontalDivider()
                                        }
                                        item {
                                            SuggestionsRow(
                                                items = sug.items,
                                                sourceId = manga.source,
                                                onItemClick = { suggestion ->
                                                    scope.launch {
                                                        val id = viewModel.openSuggestion(suggestion)
                                                        if (id != null) onMangaClick(id)
                                                    }
                                                },
                                            )
                                        }
                                        item {
                                            HorizontalDivider()
                                        }
                                    }
                                    is SuggestionsUiState.Hidden -> {}
                                }
                                item {
                                    Text(
                                        "${state.chapters.size} capítulos",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(state.chapters, key = { it.id }) { chapter ->
                                    val downloadState = downloads[chapter.id]
                                        ?: ChapterDownloadState.NotDownloaded
                                    ListItem(
                                        headlineContent = { Text(chapter.name) },
                                        supportingContent = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (chapter.read) Text("Lido")
                                                if (chapter.bookmark) Text("Marcado")
                                                if (chapter.lastPageRead > 0) Text("pág. ${chapter.lastPageRead}")
                                                when (downloadState) {
                                                    is ChapterDownloadState.Queued,
                                                    is ChapterDownloadState.Downloading,
                                                    -> {
                                                        val progress = (downloadState as? ChapterDownloadState.Downloading)?.progress
                                                        val total = when (downloadState) {
                                                            is ChapterDownloadState.Downloading -> downloadState.total
                                                            is ChapterDownloadState.Queued -> downloadState.total
                                                            else -> 0
                                                        }
                                                        Text(if (total > 0) "Baixando $progress/$total" else "Na fila")
                                                    }
                                                    is ChapterDownloadState.Downloaded -> Text("Baixado")
                                                    is ChapterDownloadState.Paused -> Text("Pausado")
                                                    is ChapterDownloadState.Failed -> Text("Falhou")
                                                    is ChapterDownloadState.NotDownloaded -> {}
                                                }
                                            }
                                        },
                                        trailingContent = {
                                            DownloadButton(
                                                state = downloadState,
                                                onDownload = { viewModel.downloadChapter(chapter.id) },
                                                onPause = { viewModel.pauseChapter(chapter.id) },
                                                onResume = { viewModel.resumeChapter(chapter.id) },
                                                onDelete = { viewModel.deleteDownload(chapter.id) },
                                            )
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier.clickable { onChapterClick(chapter.id) },
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

@Composable
private fun DetailHeader(
    title: String,
    author: String?,
    statusLabel: String,
    sourceName: String?,
    coverUrl: String?,
    sourceId: Long?,
    dominant: Color?,
    accent: Color?,
    background: Color,
    onBack: () -> Unit,
    onDownloadAll: () -> Unit,
    actions: @Composable () -> Unit,
) {
    // Altura maior: capa + título + faixa de ações dentro do cabeçalho.
    Box(modifier = Modifier.fillMaxWidth().height(390.dp)) {
        // Fundo = capa desfocada e ampliada (visual Komikku), não a capa
        // nítida: some o detalhe e o tom da arte domina o cabeçalho.
        SubcomposeAsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // clipToBounds ANTES do scale: a capa ampliada não pode
                // transbordar do header e pintar sobre a descrição.
                .clipToBounds()
                .scale(1.3f)
                .blur(40.dp),
            loading = {},
            error = {},
        )
        // Escurece para o título/autor continuarem legíveis.
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
        )
        // Dominant-color wash so the screen picks up the cover's tone.
        if (dominant != null) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(dominant.copy(alpha = 0.28f)),
            )
        }
        // Top shade for the overlaid buttons.
        Box(
            modifier = Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.45f),
                        0.35f to Color.Transparent,
                    ),
                ),
        )
        // Bottom scrim melting into the tinted background.
        Box(
            modifier = Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.78f to background,
                        1.0f to background,
                    ),
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            Row {
                IconButton(onClick = onDownloadAll) {
                    Icon(Icons.Filled.Downloading, contentDescription = "Baixar tudo", tint = Color.White)
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MangaCover(
                    coverUrl = coverUrl,
                    contentDescription = title,
                    modifier = Modifier.width(110.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)),
                    sourceId = sourceId,
                )
                Column(
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.height(16.dp),
                            tint = Color.White.copy(alpha = 0.86f),
                        )
                        Text(
                            author ?: "Autor desconhecido",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.86f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "$statusLabel${sourceName?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.86f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Ações sobre a parte do scrim que já virou cor sólida:
            // nenhuma emenda entre header e corpo.
            actions()
        }
    }
}

/**
 * Cover palette: the most-frequent color (background tint) plus a vibrant
 * accent scored by population × saturation, like Komikku's detail screen.
 */
private data class CoverPalette(
    val dominant: Color,
    val vibrant: Color,
    /**
     * [dominant] com luminosidade/saturação normalizadas: capa escura ou
     * acinzentada ainda gera um fundo com matiz visível (misturar preto sobre
     * preto não produz cor nenhuma).
     */
    val tint: Color = dominant,
)

/**
 * Extracts the cover palette for tinting the detail screen.
 * Null while loading or when unavailable.
 */
@Composable
private fun coverPalette(coverUrl: String?): State<CoverPalette?> {
    val context = LocalContext.current
    val state = remember(coverUrl) { mutableStateOf<CoverPalette?>(null) }
    LaunchedEffect(coverUrl) {
        state.value = null
        if (coverUrl.isNullOrBlank()) return@LaunchedEffect
        runCatching {
            val loader = SingletonImageLoader.get(context)
            val request = ImageRequest.Builder(context)
                .data(coverUrl)
                .build()
            val result = loader.execute(request) as? SuccessResult ?: return@runCatching
            var bitmap = (result.image as? BitmapImage)?.bitmap ?: return@runCatching
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching
            }
            state.value = withContext(Dispatchers.Default) { paletteOf(bitmap) }
        }
    }
    return state
}

private fun paletteOf(bitmap: Bitmap): CoverPalette {
    val size = 64
    val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    val buckets = HashMap<Int, Int>()
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        val quantized = pixel and 0x00F0F0F0.toInt()
        buckets[quantized] = (buckets[quantized] ?: 0) + 1
    }
    if (buckets.isEmpty()) return CoverPalette(Color.Gray, Color.Gray, tintable(Color.Gray))
    val dominantKey = buckets.maxBy { it.value }.key
    // Vibrant = populous and saturated, with readable lightness.
    var vibrantKey = dominantKey
    var bestScore = -1.0
    for ((key, count) in buckets) {
        val (r, g, b) = expandKey(key)
        val max = maxOf(r, g, b) / 255.0
        val min = minOf(r, g, b) / 255.0
        val saturation = if (max == 0.0) 0.0 else (max - min) / max
        val lightness = (max + min) / 2.0
        if (lightness < 0.2 || lightness > 0.85) continue
        val score = count * (0.3 + saturation)
        if (score > bestScore) {
            bestScore = score
            vibrantKey = key
        }
    }
    return CoverPalette(
        dominant = expandColor(dominantKey),
        vibrant = expandColor(vibrantKey),
        tint = tintable(expandColor(dominantKey)),
    )
}

/**
 * Deixa a cor apta a virar fundo: garante luminosidade mínima e uma
 * saturação mínima, preservando o matiz. Sem isso, capas escuras/acinzentadas
 * somem no preto do modo AMOLED.
 */
private fun tintable(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = hsl[1].coerceAtLeast(0.34f) // saturação
    hsl[2] = hsl[2].coerceIn(0.46f, 0.60f) // luminosidade
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun expandKey(key: Int): Triple<Int, Int, Int> {
    fun expand(nibble4: Int): Int {
        val n = (nibble4 shr 4) and 0xF
        return (n shl 4) or n
    }
    return Triple(expand((key shr 16) and 0xF0), expand((key shr 8) and 0xF0), expand(key and 0xF0))
}

private fun expandColor(key: Int): Color {
    val (r, g, b) = expandKey(key)
    return Color(r, g, b)
}

/**
 * Returns [vibrant] when it contrasts enough with [background] for large
 * text (ratio ≥ 3.0), otherwise nudges it toward [fallback] until it does.
 */
private fun readableAccent(vibrant: Color, background: Color, fallback: Color): Color {
    fun ratio(foreground: Color): Double {
        val a = foreground.luminance() + 0.05
        val b = background.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }
    var color = vibrant
    repeat(8) {
        if (ratio(color) >= 3.0) return color
        color = Color(
            red = color.red + (fallback.red - color.red) * 0.25f,
            green = color.green + (fallback.green - color.green) * 0.25f,
            blue = color.blue + (fallback.blue - color.blue) * 0.25f,
            alpha = 1f,
        )
    }
    return if (ratio(color) >= 3.0) color else fallback
}

@Composable
private fun DetailActions(
    favorite: Boolean,
    onFavoriteClick: () -> Unit,
    webUrl: String?,
    onWebClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        ActionItem(
            icon = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            label = if (favorite) "Na\nbiblioteca" else "Adicionar",
            enabled = true,
            onClick = onFavoriteClick,
        )
        ActionItem(
            icon = Icons.Filled.HourglassEmpty,
            label = "N/A",
            enabled = false,
            onClick = {},
        )
        ActionItem(
            icon = Icons.Filled.Sync,
            label = "Monitoramento",
            enabled = false,
            onClick = {},
        )
        ActionItem(
            icon = Icons.Filled.Public,
            label = "WebView",
            enabled = webUrl != null,
            onClick = { webUrl?.let(onWebClick) },
        )
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val contentAlpha = if (enabled) 1f else 0.5f
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .alpha(contentAlpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.height(24.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ExpandableDescription(description: String?, accent: Color?) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
            .clickable { expanded = !expanded },
    ) {
        Text(
            description?.ifBlank { null } ?: "Sem descrição",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
    }
}

private fun statusLabel(status: Long): String {
    return when (status.toInt()) {
        SManga.ONGOING -> "Em andamento"
        SManga.COMPLETED -> "Concluído"
        SManga.LICENSED -> "Licenciado"
        SManga.PUBLISHING_FINISHED -> "Publicação finalizada"
        SManga.CANCELLED -> "Cancelado"
        SManga.ON_HIATUS -> "Em hiato"
        else -> "Desconhecido"
    }
}

@Composable
private fun GenreChips(genres: List<String>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(genres, key = { it }) { genre ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    genre,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SuggestionsLoading() {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(3) {
            Box(
                modifier = Modifier.width(110.dp).aspectRatio(3f / 4f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun SuggestionsRow(
    items: List<SManga>,
    sourceId: Long?,
    onItemClick: (SManga) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sugestões", style = MaterialTheme.typography.titleMedium)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.url }) { suggestion ->
                Column(
                    modifier = Modifier.width(110.dp).clickable { onItemClick(suggestion) },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MangaCover(
                        coverUrl = suggestion.thumbnail_url,
                        contentDescription = suggestion.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                        sourceId = sourceId,
                    )
                    Text(
                        text = suggestion.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadButton(
    state: ChapterDownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    when (state) {
        is ChapterDownloadState.NotDownloaded -> {
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "Baixar")
            }
        }
        is ChapterDownloadState.Queued -> {
            IconButton(onClick = onPause) {
                Icon(Icons.Filled.Download, contentDescription = "Na fila (tocar para pausar)")
            }
        }
        is ChapterDownloadState.Downloading -> {
            IconButton(onClick = onPause) {
                Icon(Icons.Filled.Pause, contentDescription = "Pausar")
            }
        }
        is ChapterDownloadState.Paused -> {
            IconButton(onClick = onResume) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Continuar")
            }
        }
        is ChapterDownloadState.Downloaded -> {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Check, contentDescription = "Baixado (tocar para apagar)")
            }
        }
        is ChapterDownloadState.Failed -> {
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Error, contentDescription = "Falhou (tocar para repetir)")
            }
        }
    }
}
