package com.vauth.foxyvpn.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Host + path of the OAuth completion redirect served by Guardian. */
private const val REDIRECT_HOST = "vpn.mozilla.org"
private const val REDIRECT_PATH_PREFIX = "/oauth/success"
private const val DEEP_LINK_SCHEME = "mozilla-vpn"

/**
 * In-app browser sign-in. Loads the official Guardian login URL, which redirects to
 * accounts.firefox.com where the user signs in with a real browser engine (this is
 * what passes and automatically solves the Fastly bot challenges that block the
 * direct API password login with HTTP 406).
 *
 * When the OAuth flow completes, the browser is redirected to
 * https://vpn.mozilla.org/oauth/success?code=... (or the mozilla-vpn:// deep link);
 * this composable intercepts that navigation and reports the authorization code.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserLoginScreen(
    startUrl: String,
    busy: Boolean,
    onCodeCaptured: (String) -> Unit,
    onCanceled: () -> Unit,
) {
    var capturedCode by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var pageProgress by remember { mutableIntStateOf(0) }
    var pageLoaded by remember { mutableStateOf(false) }
    val currentOnCode by rememberUpdatedState(onCodeCaptured)
    val currentOnCanceled by rememberUpdatedState(onCanceled)

    BackHandler(enabled = true) {
        if (!busy) currentOnCanceled()
    }

    fun handleNavigation(uri: Uri): Boolean {
        if (capturedCode != null) return true
        val code = extractAuthCode(uri) ?: return false
        capturedCode = code
        currentOnCode(code)
        return true
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
        ) {
            IconButton(onClick = { if (!busy) currentOnCanceled() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel sign-in")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    pageTitle.ifBlank { "Sign in with Firefox" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    "You will return to FoxyVPN automatically after signing in",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy || !pageLoaded || pageProgress < 100) {
            LinearProgressIndicator(
                progress = { if (busy) 1f else pageProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = handleNavigation(request.url)

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                if (url != null) {
                                    if (handleNavigation(Uri.parse(url))) return
                                }
                                super.doUpdateVisitedHistory(view, url, isReload)
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                pageLoaded = false
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                pageLoaded = true
                                pageTitle = view.title.orEmpty()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageProgress = newProgress
                            }
                        }
                        loadUrl(startUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopLoading() },
            )
        }
    }
}

/** Returns the OAuth authorization code if the navigation is the flow's completion redirect. */
private fun extractAuthCode(uri: Uri): String? {
    val isGuardianRedirect = uri.scheme == "https" &&
        uri.host == REDIRECT_HOST &&
        (uri.path?.startsWith(REDIRECT_PATH_PREFIX) == true)
    val isDeepLink = uri.scheme == DEEP_LINK_SCHEME
    if (!isGuardianRedirect && !isDeepLink) return null
    return uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
}
