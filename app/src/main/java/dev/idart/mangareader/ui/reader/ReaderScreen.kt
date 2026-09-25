package dev.idart.mangareader.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.SubcomposeAsyncImage
import dev.idart.mangareader.source.model.Page
import dev.idart.mangareader.translate.PageTranslation
import dev.idart.mangareader.translate.TranslatedBlock
import dev.idart.mangareader.ui.injektViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    mangaId: Long,
    chapterId: Long,
    onBack: () -> Unit,
) {
    val viewModel: ReaderViewModel = injektViewModel(key = "reader-$mangaId-$chapterId") {
        ReaderViewModel(Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get(), Injekt.get(), mangaId, chapterId)
    }
    val state by viewModel.state.collectAsState()
    val translationEnabled by viewModel.translationEnabled.collectAsState()
    val translations by viewModel.translations.collectAsState()

    // Modo imersivo: chrome (topo + barras do sistema) escondido por padrão,
    // revelado ao tocar o centro da tela. rememberSaveable sobrevive à rotação.
    var chromeVisible by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current

    DisposableEffect(chromeVisible) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chromeVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = window?.let {
                WindowCompat.getInsetsController(it, view)
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Auto-oculta de novo depois de um tempo com o chrome aberto.
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(4000)
            chromeVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Faixa central (verticais 30–70%, horizontais 12–88%): alterna
                    // o chrome. Toques fora dela (bordas / gesto de segurar) não
                    // fazem nada, evitando toggle acidental durante o scroll.
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (h > 0 && w > 0 &&
                        offset.y in h * 0.30f..h * 0.70f &&
                        offset.x in w * 0.12f..w * 0.88f
                    ) {
                        chromeVisible = !chromeVisible
                    }
                }
            },
    ) {
        // O padding() do Scaffold antigo desaparece: o quadrinho ocupa tudo.
        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            state.error != null -> Text(
                text = "Erro: ${state.error}",
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, chapterId) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        listState.firstVisibleItemIndex to
                            (info.visibleItemsInfo.lastOrNull()?.index ?: -1)
                    }
                        .distinctUntilChanged()
                        .collect { (firstVisible, lastVisible) ->
                            viewModel.onFeedPositionChanged(firstVisible)
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0 && lastVisible >= total - 2) {
                                viewModel.loadMore()
                            }
                        }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = state.feed.size,
                        key = { index ->
                            when (val item = state.feed[index]) {
                                is ReaderFeedItem.PageItem -> "${item.chapterId}-${item.page.index}"
                                is ReaderFeedItem.ChapterHeader -> "header-${item.chapterId}"
                                is ReaderFeedItem.Transition -> "transition-${item.toChapterId}"
                                ReaderFeedItem.EndMarker -> "end"
                                ReaderFeedItem.LoadingMore -> "loading"
                            }
                        },
                    ) { index ->
                        when (val item = state.feed[index]) {
                            is ReaderFeedItem.ChapterHeader -> {
                                Text(
                                    item.name ?: "Capítulo",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 20.dp),
                                )
                            }
                            is ReaderFeedItem.PageItem -> {
                                val key = viewModel.pageKey(item.chapterId, item.page.index)
                                ReaderPage(
                                    page = item.page,
                                    contentDescription = "Página ${item.page.index + 1}",
                                    translation = if (translationEnabled) translations[key] else null,
                                )
                            }
                            is ReaderFeedItem.Transition -> {
                                ChapterTransition(
                                    fromName = item.fromName,
                                    toName = item.toName,
                                    modifier = Modifier.fillParentMaxHeight(0.6f),
                                )
                            }
                            ReaderFeedItem.EndMarker -> {
                                Text(
                                    "Último capítulo",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                )
                            }
                            ReaderFeedItem.LoadingMore -> {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                    if (state.reachedEnd) {
                        item(key = "end-marker") {
                            Text(
                                "Último capítulo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(vertical = 32.dp),
                            )
                        }
                    } else if (state.loadMoreError != null) {
                        item(key = "load-error") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Erro: ${state.loadMoreError}")
                                Button(onClick = { viewModel.retryLoadMore() }) {
                                    Text("Repetir")
                                }
                            }
                        }
                    } else if (state.loadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }

        // ----- Chrome superior: TopAppBar + barra de progresso -----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it },
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            // Engole o toque da faixa do chrome: senão passaria para o
            // detector do Box e fecharia o chrome no mesmo instante.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                        ),
                    )
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                state.currentChapterName ?: "Leitor",
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.totalPages > 0) {
                                Text(
                                    "Página " +
                                        state.currentPage
                                            .coerceAtMost(state.totalPages)
                                            .coerceAtLeast(1) +
                                        " de ${state.totalPages}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = "Voltar",
                                tint = Color.White,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.setTranslationEnabled(!translationEnabled) },
                        ) {
                            Icon(
                                Icons.Filled.Translate,
                                contentDescription = if (translationEnabled) {
                                    "Ocultar tradução"
                                } else {
                                    "Traduzir página"
                                },
                                tint = if (translationEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.85f)
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    modifier = Modifier.statusBarsPadding(),
                )
                if (state.totalPages > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (
                                state.currentPage
                                    .coerceAtMost(state.totalPages)
                                    .toFloat() / state.totalPages.toFloat()
                                )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ----- Chrome inferior: número da página -----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${state.currentPage.coerceAtMost(state.totalPages).coerceAtLeast(1)} / ${state.totalPages}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ChapterTransition(
    fromName: String?,
    toName: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text(
            "Indo de",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            fromName ?: "Capítulo anterior",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.height(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "para",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            toName ?: "Próximo capítulo",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ReaderPage(
    page: Page,
    contentDescription: String?,
    translation: PageTranslation?,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        SubcomposeAsyncImage(
            model = page.imageUrl,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
            loading = {
                Box(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Falha ao carregar imagem", style = MaterialTheme.typography.bodySmall)
                }
            },
        )
        if (translation is PageTranslation.Done) {
            var overlaySize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier.matchParentSize()
                    .onSizeChanged { overlaySize = it },
            ) {
                val scaleX = if (translation.imageWidth > 0) {
                    overlaySize.width / translation.imageWidth.toFloat()
                } else {
                    1f
                }
                val scaleY = if (translation.imageHeight > 0) {
                    overlaySize.height / translation.imageHeight.toFloat()
                } else {
                    scaleX
                }
                translation.blocks.forEach { block ->
                    TranslatedBubble(
                        block = block,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        imageWidth = translation.imageWidth,
                        imageHeight = translation.imageHeight,
                    )
                }
            }
        } else if (translation is PageTranslation.Working) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).padding(top = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun TranslatedBubble(
    block: TranslatedBlock,
    scaleX: Float,
    scaleY: Float,
    imageWidth: Int,
    imageHeight: Int,
) {
    val density = LocalDensity.current
    // O OCR retorna a caixa do TEXTO, menor que o balão. Infla ~12% de cada
    // lado para cobrir o balão e esconder o original.
    val rawW = (block.right - block.left).toFloat()
    val rawH = (block.bottom - block.top).toFloat()
    val padX = (rawW * 0.12f).coerceAtLeast(6f)
    val padY = (rawH * 0.12f).coerceAtLeast(6f)
    val left = (block.left - padX).coerceAtLeast(0f)
    val top = (block.top - padY).coerceAtLeast(0f)
    val right = (block.right + padX).let { if (imageWidth > 0) it.coerceAtMost(imageWidth.toFloat()) else it }
    val bottom = (block.bottom + padY).let { if (imageHeight > 0) it.coerceAtMost(imageHeight.toFloat()) else it }
    val widthDp = with(density) { ((right - left) * scaleX).toDp() }
    val heightDp = with(density) { ((bottom - top) * scaleY).toDp() }
    if (widthDp.value <= 0f || heightDp.value <= 0f) return
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current

    // Maior fonte que cabe no balão: o limite de linhas vem da altura, então
    // o overflow vertical é detectado de forma confiável. Encolhe até caber
    // (mín. 6sp) e cresce enquanto couber (máx. 17sp). Se nem no mínimo
    // couber, a bolha CRESCE (até 2.5x altura / 2x largura) em vez de cortar
    // o texto ao meio.
    data class BubbleLayout(
        val left: Float,
        val top: Float,
        val widthDp: Dp,
        val heightDp: Dp,
        val sizeSp: Float,
        val lines: Int,
    )
    val layout = remember(block.translated, widthDp, heightDp, measurer, baseStyle) {
        val maxWidthPx = maxOf(1, with(density) { (widthDp - 4.dp).toPx() }.roundToInt())
        val maxHeightPx = maxOf(1, with(density) { (heightDp - 4.dp).toPx() }.roundToInt())
        fun linesFor(sizeSp: Float, heightPx: Int): Int {
            val linePx = with(density) { (sizeSp * 1.15f).sp.toPx() }
            return maxOf(1, (heightPx / linePx).toInt())
        }
        fun styleFor(sizeSp: Float) = baseStyle.copy(
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * 1.15f).sp,
            textAlign = TextAlign.Center,
            color = Color.Black,
        )
        fun fits(sizeSp: Float, heightPx: Int): Boolean {
            val result = measurer.measure(
                text = block.translated,
                style = styleFor(sizeSp),
                maxLines = linesFor(sizeSp, heightPx),
                constraints = Constraints(maxWidth = maxWidthPx, maxHeight = heightPx),
            )
            return !result.hasVisualOverflow
        }
        var size = (heightDp.value * 0.34f).coerceIn(MIN_BUBBLE_SP, MAX_BUBBLE_SP)
        while (size > MIN_BUBBLE_SP && !fits(size, maxHeightPx)) {
            size -= 1f
        }
        while (size < MAX_BUBBLE_SP && fits(size + 1f, maxHeightPx)) {
            size += 1f
        }
        if (fits(size, maxHeightPx)) {
            BubbleLayout(left, top, widthDp, heightDp, size, linesFor(size, maxHeightPx))
        } else {
            val need = measurer.measure(
                text = block.translated,
                style = styleFor(MIN_BUBBLE_SP),
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Visible,
                constraints = Constraints(maxWidth = maxWidthPx),
            )
            val padPx = with(density) { 4.dp.toPx() }
            val boxW = (right - left).coerceAtLeast(1f)
            val boxH = (bottom - top).coerceAtLeast(1f)
            val needW = (need.size.width + padPx).coerceAtMost(maxWidthPx * 2f)
            val needH = (need.size.height + padPx).coerceAtMost(maxHeightPx * 2.5f)
            val newW = maxOf(boxW, needW / scaleX.coerceAtLeast(0.01f))
            val newH = maxOf(boxH, needH / scaleY.coerceAtLeast(0.01f))
            val cappedW = if (imageWidth > 0) newW.coerceAtMost(imageWidth.toFloat()) else newW
            val cappedH = if (imageHeight > 0) newH.coerceAtMost(imageHeight.toFloat()) else newH
            val newLeft = ((left + boxW / 2f - cappedW / 2f))
                .coerceAtLeast(0f)
                .let { if (imageWidth > 0) it.coerceAtMost((imageWidth - cappedW).coerceAtLeast(0f)) else it }
            val newTop = ((top + boxH / 2f - cappedH / 2f))
                .coerceAtLeast(0f)
                .let { if (imageHeight > 0) it.coerceAtMost((imageHeight - cappedH).coerceAtLeast(0f)) else it }
            val fullFit = needW <= cappedW * scaleX && needH <= cappedH * scaleY
            val grownHpx = maxOf(1, with(density) { (with(density) { (cappedH * scaleY).toDp() } - 4.dp).toPx() }.roundToInt())
            BubbleLayout(
                left = newLeft,
                top = newTop,
                widthDp = with(density) { (cappedW * scaleX).toDp() },
                heightDp = with(density) { (cappedH * scaleY).toDp() },
                sizeSp = MIN_BUBBLE_SP,
                lines = if (fullFit) Int.MAX_VALUE else linesFor(MIN_BUBBLE_SP, grownHpx),
            )
        }
    }
    Box(
        modifier = Modifier
            .offset { IntOffset((layout.left * scaleX).roundToInt(), (layout.top * scaleY).roundToInt()) }
            .size(layout.widthDp, layout.heightDp)
            .background(Color.White.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = block.translated,
            fontSize = layout.sizeSp.sp,
            color = Color.Black,
            maxLines = layout.lines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = (layout.sizeSp * 1.15f).sp,
            modifier = Modifier.padding(2.dp),
        )
    }
}

private const val MIN_BUBBLE_SP = 6f
private const val MAX_BUBBLE_SP = 17f
