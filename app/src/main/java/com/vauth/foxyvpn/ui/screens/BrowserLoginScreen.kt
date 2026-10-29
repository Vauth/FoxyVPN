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
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.ControlPlaneHttp

private const val TAG = "BrowserLoginScreen"

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
 * https://vpn.mozilla.org/oauth/success?code=... (or the mozilla-vpn:// deep link).
 * The completion navigation MUST be allowed to reach Guardian: Guardian registers
 * the authorization code server-side only when the browser actually GETs that URL
 * (carrying the flow session cookie). Blocking it — as earlier builds did — makes
 * the final /login/verify call fail with "'code' not found or expired". The code
 * is reported from doUpdateVisitedHistory, i.e. after the request was answered.
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

    // Pre-load interception. Only the mozilla-vpn:// deep link is blocked (it can
    // never be loaded by the WebView anyway); every https navigation — including
    // the final /oauth/success?code=... completion redirect — must be allowed to
    // go through so Guardian can register the code server-side.
    fun handleOverride(uri: Uri): Boolean {
        if (capturedCode != null) return true
        if (uri.scheme == DEEP_LINK_SCHEME) {
            extractAuthCode(uri)?.let { code ->
                capturedCode = code
                currentOnCode(code)
            }
            return true
        }
        return false
    }

    // Fired once a navigation is committed (the server has already responded), so
    // the completion GET has reached Guardian by this point and the code can be
    // exchanged safely.
    fun handleHistoryUpdate(uri: Uri) {
        if (capturedCode != null) return
        val code = extractAuthCode(uri) ?: return
        capturedCode = code
        currentOnCode(code)
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
                            ): Boolean = handleOverride(request.url)

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                if (url != null) handleHistoryUpdate(Uri.parse(url))
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

/**
 * Returns the OAuth authorization code if the navigation is the flow's completion
 * redirect. The official redirect target is https://vpn.mozilla.org/oauth/success
 * (query carries both `code` and `state`); the mozilla-vpn:// deep link is matched
 * as a fallback because Guardian's success page may bounce through it.
 */
private fun extractAuthCode(uri: Uri): String? {
    val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null
    if (uri.scheme == DEEP_LINK_SCHEME) return code
    val isGuardianSuccess = uri.scheme == "https" &&
        uri.host == REDIRECT_HOST &&
        (uri.path?.startsWith(REDIRECT_PATH_PREFIX) == true ||
            uri.getQueryParameter("state")?.startsWith("pkce-") == true)
    return if (isGuardianSuccess) code else null
}

/**
 * Guardian's /oauth/success completion page must be requested by the WebView while
 * carrying the flow session cookie that Guardian issued when the app started the
 * login flow; without the session, Guardian cannot match the code to the flow and
 * /login/verify later fails with "'code' not found or expired". The WebView keeps
 * its own cookie store, so copy the flow cookies over before the sign-in page is
 * loaded.
 */
internal fun syncGuardianCookiesToWebView() {
    val manager = CookieManager.getInstance()
    for (cookie in ControlPlaneHttp.cookieJar.snapshotAll()) {
        if (!cookie.domain.endsWith("mozilla.org")) continue
        val header = buildString {
            append(cookie.name).append('=').append(cookie.value)
            append("; Path=").append(cookie.path.ifBlank { "/" })
            if (cookie.secure) append("; Secure")
        }
        runCatching { manager.setCookie("https://${cookie.domain}/", header) }
            .onFailure { AppLogger.w(TAG, "could not sync cookie ${cookie.name} into the WebView", it) }
    }
    manager.flush()
}

/**
 * Mirror whatever cookies the WebView ended up with for vpn.mozilla.org (Guardian
 * may have rotated the flow session while serving /oauth/success) back into the
 * shared jar used by the final /login/verify call.
 */
internal fun syncWebViewCookiesToGuardianJar() {
    val raw = CookieManager.getInstance().getCookie("https://vpn.mozilla.org/") ?: return
    for (pair in raw.split(";")) {
        val idx = pair.indexOf('=')
        if (idx <= 0) continue
        val name = pair.substring(0, idx).trim()
        val value = pair.substring(idx + 1).trim()
        if (name.isEmpty() || value.isEmpty()) continue
        ControlPlaneHttp.cookieJar.set(name, value, "vpn.mozilla.org")
    }
}
