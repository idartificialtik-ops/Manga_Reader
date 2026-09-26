package dev.idart.mangareader.ui.sourcewebview

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.idart.mangareader.ui.injektViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.network.NetworkHelper as ExtNetworkHelper

/**
 * Manual Cloudflare (and login) WebView.
 *
 * The automatic headless attempt cannot click a checkbox or read a CAPTCHA, so
 * this screen exists for when the site insists: solve it here with the user
 * present, and the cookies are copied into the jar the source reads from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWebViewScreen(
    sourceId: Long,
    onBack: () -> Unit,
) {
    val viewModel: SourceWebViewViewModel = injektViewModel {
        SourceWebViewViewModel(Injekt.get(), Injekt.get<ExtNetworkHelper>(), sourceId)
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var editingUrl by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val webView = remember(context) { WebView(context) }

    // WebView is main-thread only and leaks unless destroyed by hand.
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // Attached and configured before the first load (which happens after
    // composition). WebView has JavaScript OFF by default — without this the
    // challenge script never runs and the interstitial stays empty.
    val userAgent = state.userAgent
    remember(webView, userAgent) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // target=_blank (login flows) must land in this WebView.
            setSupportMultipleWindows(false)
            userAgent?.let { userAgentString = it }
        }
        // The Turnstile widget runs in an iframe on another host: without
        // third-party cookies it can never complete.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                url?.let {
                    currentUrl = it
                    editingUrl = it
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                viewModel.onBlocked(false)
                // Solving the challenge is enough: whatever cookie the site
                // set goes straight to the jar.
                url?.let {
                    viewModel.saveCookies(it)
                    viewModel.refreshStored(it)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                // Sub-resources 403 all the time; only the main frame means the
                // challenge is still up.
                if (request?.isForMainFrame == true && errorResponse?.statusCode in CHALLENGE_CODES) {
                    viewModel.onBlocked(true)
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(state.startUrl) {
        if (state.startUrl.isNotBlank() && currentUrl.isBlank()) {
            editingUrl = state.startUrl
            webView.loadUrl(state.startUrl)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.sourceName.ifBlank { "WebView" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Recarregar")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = editingUrl,
                    onValueChange = { editingUrl = it },
                    singleLine = true,
                    label = { Text("Endereço") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { webView.loadUrl(editingUrl.trim()) }) { Text("Ir") }
            }
            StatusCard(state = state, onClear = { viewModel.clearCookies(currentUrl) })
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StatusCard(
    state: SourceWebViewUiState,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.blocked) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = when {
                    // 403 from the main frame: the interstitial is the error.
                    state.blocked ->
                        "O site respondeu 403. Role a página e conclua a verificação " +
                            "(verificação de idade, \"não sou robô\") — os cookies são salvos " +
                            "sozinhos quando ela passa."
                    // A page loaded but set nothing: it is an interstitial.
                    state.awaitingInteraction ->
                        "A página abriu, mas é uma tela de verificação. Role até o aviso " +
                            "(\"sou maior de 18\", \"não sou robô\") e confirme. " +
                            "JavaScript e cookies já estão ligados."
                    else -> "${state.storedCookies} cookie(s) salvos para esta fonte."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            state.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.storedCookies > 0) {
                TextButton(onClick = onClear) { Text("Limpar cookies") }
            }
        }
    }
}

private val CHALLENGE_CODES = setOf(403, 503)
