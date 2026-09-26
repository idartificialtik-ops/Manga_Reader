package dev.idart.mangareader.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.idart.mangareader.ui.ai.AiScreen
import dev.idart.mangareader.ui.browse.BrowseScreen
import dev.idart.mangareader.ui.browse.GlobalSearchScreen
import dev.idart.mangareader.ui.browse.SourceBrowseScreen
import dev.idart.mangareader.ui.downloads.DownloadsScreen
import dev.idart.mangareader.ui.extensions.ExtensionsScreen
import dev.idart.mangareader.ui.feed.FeedScreen
import dev.idart.mangareader.ui.library.LibraryScreen
import dev.idart.mangareader.ui.manga.MangaDetailScreen
import dev.idart.mangareader.ui.more.MoreScreen
import dev.idart.mangareader.ui.more.ThemeScreen
import dev.idart.mangareader.ui.more.TranslationSettingsScreen
import dev.idart.mangareader.ui.reader.ReaderScreen
import dev.idart.mangareader.ui.sourceconfig.SourceConfigScreen
import dev.idart.mangareader.ui.sourcewebview.SourceWebViewScreen

private data class TopLevelDestination(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

private val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(Library, "Biblioteca", Icons.AutoMirrored.Filled.LibraryBooks),
    TopLevelDestination(Browse, "Navegar", Icons.Filled.Explore),
    TopLevelDestination(Feed, "Feed", Icons.Filled.RssFeed),
    TopLevelDestination(Downloads, "Downloads", Icons.Filled.Download),
    TopLevelDestination(More, "Mais", Icons.Filled.MoreHoriz),
)

@Composable
fun MangaReaderNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val showBottomBar = destination?.hierarchy?.any { navDestination ->
        TOP_LEVEL_DESTINATIONS.any { topLevel -> navDestination.hasRoute(topLevel.route::class) }
    } == true
    // Atalho global pro chat IA no canto inferior direito — em todas as
    // telas, menos no Leitor (taparia o quadrinho) e no próprio chat.
    val showAiFab = destination?.hierarchy?.any {
        it.hasRoute(Reader::class) || it.hasRoute(AiAssistant::class)
    } != true

    Scaffold(
        // Screens own their bars/insets (each has its own Scaffold or custom
        // handling); the outer Scaffold must not consume system bars twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (showAiFab) {
                FloatingActionButton(
                    onClick = {
                        navController.navigate(AiAssistant) { launchSingleTop = true }
                    },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = "Assistente IA",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_LEVEL_DESTINATIONS.forEach { topLevel ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(topLevel.route::class) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(topLevel.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(topLevel.icon, contentDescription = topLevel.label) },
                            label = { Text(topLevel.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Library,
            modifier = Modifier.padding(padding),
        ) {
            composable<Library> {
                LibraryScreen(onMangaClick = { mangaId ->
                    navController.navigate(MangaDetail(mangaId))
                })
            }
            composable<Browse> {
                BrowseScreen(
                    onSourceClick = { sourceId ->
                        navController.navigate(SourceBrowse(sourceId))
                    },
                    onSearchClick = {
                        navController.navigate(GlobalSearch())
                    },
                )
            }
            composable<GlobalSearch> { entry ->
                val route = entry.toRoute<GlobalSearch>()
                GlobalSearchScreen(
                    initialQuery = route.initialQuery,
                    onBack = { navController.popBackStack() },
                    onMangaOpen = { mangaId ->
                        navController.navigate(MangaDetail(mangaId))
                    },
                    onSeeMore = { sourceId, query ->
                        navController.navigate(SourceBrowse(sourceId, query))
                    },
                )
            }
            composable<Feed> {
                FeedScreen(
                    onMangaOpen = { mangaId ->
                        navController.navigate(MangaDetail(mangaId))
                    },
                    onSearchTitle = { title ->
                        navController.navigate(GlobalSearch(title))
                    },
                    onAiClick = {
                        navController.navigate(AiAssistant) { launchSingleTop = true }
                    },
                )
            }
            composable<More> {
                MoreScreen(
                    onExtensionsClick = {
                        navController.navigate(Extensions)
                    },
                    onThemeClick = {
                        navController.navigate(Theme)
                    },
                    onAiClick = {
                        navController.navigate(AiAssistant)
                    },
                    onTranslationClick = {
                        navController.navigate(TranslationSettings)
                    },
                )
            }
            composable<TranslationSettings> {
                TranslationSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onAiClick = { navController.navigate(AiAssistant) },
                )
            }
            composable<AiAssistant> {
                AiScreen(
                    onBack = { navController.popBackStack() },
                    onMangaOpen = { mangaId ->
                        navController.navigate(MangaDetail(mangaId))
                    },
                )
            }
            composable<Theme> {
                ThemeScreen(onBack = { navController.popBackStack() })
            }
            composable<Extensions> {
                ExtensionsScreen(
                    onBack = { navController.popBackStack() },
                    onSourceConfig = { id -> navController.navigate(SourceConfig(id)) },
                    onSourceWebView = { id -> navController.navigate(SourceWebView(id)) },
                )
            }
            composable<Downloads> {
                DownloadsScreen(
                    onChapterOpen = { mangaId, chapterId ->
                        navController.navigate(Reader(mangaId, chapterId))
                    },
                )
            }
            composable<MangaDetail> { entry ->
                val route = entry.toRoute<MangaDetail>()
                MangaDetailScreen(
                    mangaId = route.mangaId,
                    onBack = { navController.popBackStack() },
                    onChapterClick = { chapterId ->
                        navController.navigate(Reader(route.mangaId, chapterId))
                    },
                    onMangaClick = { mangaId ->
                        navController.navigate(MangaDetail(mangaId))
                    },
                )
            }
            composable<SourceBrowse> { entry ->
                val route = entry.toRoute<SourceBrowse>()
                SourceBrowseScreen(
                    sourceId = route.sourceId,
                    initialQuery = route.initialQuery,
                    onBack = { navController.popBackStack() },
                    onMangaOpen = { mangaId ->
                        navController.navigate(MangaDetail(mangaId))
                    },
                    onConfigClick = { id ->
                        navController.navigate(SourceConfig(id))
                    },
                    onWebViewClick = { id ->
                        navController.navigate(SourceWebView(id))
                    },
                )
            }
            composable<SourceConfig> { entry ->
                val route = entry.toRoute<SourceConfig>()
                SourceConfigScreen(
                    sourceId = route.sourceId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<SourceWebView> { entry ->
                val route = entry.toRoute<SourceWebView>()
                SourceWebViewScreen(
                    sourceId = route.sourceId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<Reader> { entry ->
                val route = entry.toRoute<Reader>()
                ReaderScreen(
                    mangaId = route.mangaId,
                    chapterId = route.chapterId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
