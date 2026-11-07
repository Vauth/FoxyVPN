package com.vauth.foxyvpn.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.FirefoxAccountHeaders
import java.lang.ref.WeakReference
import org.json.JSONObject

private const val TAG = "BrowserLogin"

private const val FXA_CHANNEL_ID = "account_updates"
private const val CMD_FXA_STATUS = "fxaccounts:fxa_status"
private const val CMD_CAN_LINK_ACCOUNT = "fxaccounts:can_link_account"
private const val CMD_LOGIN = "fxaccounts:login"

private val SESSION_TOKEN_HEX = Regex("^[0-9a-f]{64}$")

private fun jsString(value: String): String = JSONObject.quote(value)

private val WEBCHANNEL_HOOK_JS = """
(function() {
  if (window.__foxyChannelHooked) return;
  window.__foxyChannelHooked = true;
  var send = function(o) {
    try {
      var host = window.location.hostname;
      if (host !== 'accounts.firefox.com' && !host.endsWith('.firefox.com')) return;
      var payload = typeof o === 'string' ? o : JSON.stringify(o);
      foxyWebChannel.onMessage(payload, window.location.href);
    } catch (e) {}
  };
  var onChannel = function(e) {
    var d = e.detail;
    if (typeof d === 'string') { try { d = JSON.parse(d); } catch (e) { return; } }
    if (d && d.message) send(d);
  };
  window.addEventListener('WebChannelMessageToChrome', onChannel);
  document.addEventListener('WebChannelMessageToChrome', onChannel);
  window.__foxyReply = function(id, messageId, command, data) {
    var detail = { id: id, message: { messageId: messageId, command: command, data: data } };
    window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
    document.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
  };
})();
""".trimIndent()

internal class WebChannelBridge(
    webView: WebView,
    private val onSessionToken: (String) -> Unit,
) {
    private val webViewRef = WeakReference(webView)
    @Volatile
    private var lastSessionToken: String? = null

    @JavascriptInterface
    fun onMessage(payload: String) {
        onMessage(payload, "https://accounts.firefox.com")
    }

    @JavascriptInterface
    fun onMessage(payload: String, originUrl: String) {
        val host = runCatching { Uri.parse(originUrl).host?.lowercase() }.getOrNull()
        if (host != "accounts.firefox.com" && host?.endsWith(".firefox.com") != true) {
            return
        }
        val envelope = runCatching {
            JSONObject(payload)
        }.getOrNull() ?: return
        val channelId = envelope.optString("id").ifBlank { FXA_CHANNEL_ID }
        val message = envelope.optJSONObject("message") ?: JSONObject()
        val command = message.optString("command")
        val data = message.optJSONObject("data") ?: JSONObject()
        val messageId = message.optString("messageId")

        when (command) {
            CMD_FXA_STATUS -> reply(
                channelId,
                messageId,
                CMD_FXA_STATUS,
                """{"capabilities":{},"signedInUser":null}""",
            )
            CMD_CAN_LINK_ACCOUNT -> reply(
                channelId,
                messageId,
                CMD_CAN_LINK_ACCOUNT,
                """{"ok":true}""",
            )
            CMD_LOGIN, "fxaccounts:oauth_login" -> {
                val token = data.optString("sessionToken", "").trim()
                    .ifBlank { data.optString("token", "").trim() }
                if (SESSION_TOKEN_HEX.matches(token)) {
                    notifySessionToken(token)
                }
                if (messageId.isNotBlank()) {
                    reply(channelId, messageId, command, """{"ok":true}""")
                }
            }
            else -> {
                if (messageId.isNotBlank()) {
                    reply(channelId, messageId, command, """{"ok":true}""")
                }
            }
        }
    }

    private fun notifySessionToken(token: String) {
        if (!SESSION_TOKEN_HEX.matches(token) || token == lastSessionToken) return
        lastSessionToken = token
        runCatching {
            CookieManager.getInstance().flush()
        }
        val view = webViewRef.get()
        if (view != null) {
            view.post { onSessionToken(token) }
        } else {
            onSessionToken(token)
        }
    }

    private fun reply(channelId: String, messageId: String, command: String, dataJson: String) {
        val view = webViewRef.get() ?: return
        val script = "window.__foxyReply && window.__foxyReply(" +
            "${jsString(channelId)}, ${jsString(messageId)}, ${jsString(command)}, $dataJson);"
        view.post {
            runCatching { view.evaluateJavascript(script, null) }
                .onFailure { AppLogger.w(TAG, "could not answer webchannel command $command", it) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserLoginScreen(
    startUrl: String,
    busy: Boolean,
    errorMessage: String? = null,
    onSessionCaptured: (String) -> Unit,
    onCanceled: () -> Unit,
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var bridgeInstance by remember { mutableStateOf<WebChannelBridge?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var pageProgress by remember { mutableIntStateOf(0) }
    var pageLoaded by remember { mutableStateOf(false) }
    val currentOnSession by rememberUpdatedState(onSessionCaptured)
    val currentOnCanceled by rememberUpdatedState(onCanceled)

    BackHandler(enabled = true) {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            currentOnCanceled()
        }
    }

    fun handleSession(token: String) {
        currentOnSession(token)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            IconButton(
                onClick = {
                    if (webViewInstance?.canGoBack() == true) {
                        webViewInstance?.goBack()
                    } else {
                        currentOnCanceled()
                    }
                },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    pageTitle.ifBlank { stringResource(R.string.login_title) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.browser_login_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (!pageLoaded || pageProgress < 100) {
            LinearProgressIndicator(
                progress = { pageProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webViewInstance = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString = FirefoxAccountHeaders.browserUserAgent
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        val bridge = WebChannelBridge(this, ::handleSession)
                        bridgeInstance = bridge
                        addJavascriptInterface(bridge, "foxyWebChannel")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val scheme = request.url.scheme?.lowercase().orEmpty()
                                if (scheme != "http" && scheme != "https") {
                                    return false
                                }
                                val host = request.url.host?.lowercase().orEmpty()
                                if (host == "accounts.firefox.com" ||
                                    host.endsWith(".firefox.com") ||
                                    host == "mozilla.org" ||
                                    host.endsWith(".mozilla.org")
                                ) {
                                    return false
                                }
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, request.url).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    view.context.startActivity(intent)
                                }
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                pageLoaded = false
                                runCatching { view.evaluateJavascript(WEBCHANNEL_HOOK_JS, null) }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                pageLoaded = true
                                pageTitle = view.title.orEmpty()
                                runCatching {
                                    CookieManager.getInstance().flush()
                                }
                                runCatching { view.evaluateJavascript(WEBCHANNEL_HOOK_JS, null) }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageProgress = newProgress
                            }

                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                                message?.message()?.let { AppLogger.d(TAG, "console: $it") }
                                return super.onConsoleMessage(message)
                            }
                        }
                        loadUrl(startUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    webViewInstance = null
                    bridgeInstance = null
                    view.stopLoading()
                    view.destroy()
                },
            )
        }
    }
}
