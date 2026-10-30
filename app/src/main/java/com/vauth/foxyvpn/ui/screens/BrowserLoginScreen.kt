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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vauth.foxyvpn.data.AppLogger
import java.lang.ref.WeakReference
import org.json.JSONObject

private const val TAG = "BrowserLogin"

private const val FXA_CHANNEL_ID = "account_updates"
private const val CMD_FXA_STATUS = "fxaccounts:fxa_status"
private const val CMD_CAN_LINK_ACCOUNT = "fxaccounts:can_link_account"
private const val CMD_LOGIN = "fxaccounts:login"

private val SESSION_TOKEN_HEX = Regex("^[0-9a-f]{64}$")

private const val FIREFOX_ANDROID_UA =
    "Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0"

private fun jsString(value: String): String = JSONObject.quote(value)

private val WEBCHANNEL_HOOK_JS = """
(function() {
  if (window.__foxyChannelHooked) return;
  window.__foxyChannelHooked = true;
  var forward = function (detail) {
    try {
      var host = window.location.hostname;
      if (host !== 'accounts.firefox.com' && !host.endsWith('.firefox.com')) return;
      var payload = typeof detail === 'string' ? detail : JSON.stringify(detail);
      foxyWebChannel.onMessage(payload, window.location.href);
    } catch (e) {}
  };
  window.addEventListener('WebChannelMessageToChrome', function (e) { forward(e.detail); });
  document.addEventListener('WebChannelMessageToChrome', function (e) { forward(e.detail); });
  window.__foxyReply = function (channelId, messageId, command, dataJson) {
    try {
      var parsedData = typeof dataJson === 'string' ? JSON.parse(dataJson) : dataJson;
      var envelope = {
        id: channelId,
        message: { messageId: messageId, command: command, data: parsedData }
      };
      var eventObj = new CustomEvent('WebChannelMessageToContent', { detail: envelope });
      window.dispatchEvent(eventObj);
      document.dispatchEvent(eventObj);
      var eventStr = new CustomEvent('WebChannelMessageToContent', { detail: JSON.stringify(envelope) });
      window.dispatchEvent(eventStr);
      document.dispatchEvent(eventStr);
    } catch (e) {}
  };
  var getUrlString = function(arg) {
    if (!arg) return '';
    if (typeof arg === 'string') return arg;
    if (typeof arg.url === 'string') return arg.url;
    if (typeof arg.href === 'string') return arg.href;
    try { return arg.toString(); } catch (e) { return ''; }
  };
  var checkVerifyUrl = function(url) {
    var u = getUrlString(url).toLowerCase();
    return u.indexOf('/verify') !== -1 || u.indexOf('/totp') !== -1 || u.indexOf('/session/verify') !== -1;
  };
  var checkSuccessUrl = function(url) {
    var u = getUrlString(url).toLowerCase();
    return u.indexOf('confirmed') !== -1 || u.indexOf('connect_another_device') !== -1 ||
           u.indexOf('/pair') !== -1 || u.indexOf('/settings') !== -1 || u.indexOf('/complete') !== -1 ||
           u.indexOf('success') !== -1;
  };
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function() {
      var reqUrl = (arguments[0] && arguments[0].url) || arguments[0] || '';
      return origFetch.apply(this, arguments).then(function(res) {
        try {
          if (res && res.ok && checkVerifyUrl(reqUrl)) {
            foxyWebChannel.onSessionVerified();
          }
        } catch (e) {}
        return res;
      });
    };
  }
  if (window.XMLHttpRequest) {
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
      this.__foxyReqUrl = url;
      return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
      this.addEventListener('load', function() {
        try {
          if (this.status >= 200 && this.status < 300 && checkVerifyUrl(this.__foxyReqUrl)) {
            foxyWebChannel.onSessionVerified();
          }
        } catch (e) {}
      });
      return origSend.apply(this, arguments);
    };
  }
  var notifyNav = function() {
    try {
      if (checkSuccessUrl(window.location.href)) {
        foxyWebChannel.onSessionVerified();
      }
    } catch (e) {}
  };
  var origPushState = history.pushState;
  if (origPushState) {
    history.pushState = function() {
      var ret = origPushState.apply(this, arguments);
      notifyNav();
      return ret;
    };
  }
  var origReplaceState = history.replaceState;
  if (origReplaceState) {
    history.replaceState = function() {
      var ret = origReplaceState.apply(this, arguments);
      notifyNav();
      return ret;
    };
  }
  window.addEventListener('popstate', notifyNav);
  window.addEventListener('hashchange', notifyNav);
})();
""".trimIndent()

internal class WebChannelBridge(
    webView: WebView,
    private val onSessionToken: (String) -> Unit,
) {
    private val webViewRef = WeakReference(webView)
    @Volatile
    var pendingSessionToken: String? = null
        private set

    @JavascriptInterface
    fun onMessage(payload: String) {
        onMessage(payload, "https://accounts.firefox.com")
    }

    @JavascriptInterface
    fun onSessionVerified() {
        val token = pendingSessionToken ?: return
        notifySessionToken(token)
    }

    @JavascriptInterface
    fun onMessage(payload: String, originUrl: String) {
        val host = runCatching { Uri.parse(originUrl).host?.lowercase() }.getOrNull()
        if (host != "accounts.firefox.com" && host?.endsWith(".firefox.com") != true) {
            return
        }
        val message = runCatching {
            JSONObject(payload).optJSONObject("message") ?: JSONObject()
        }.getOrNull() ?: return
        val command = message.optString("command")
        val data = message.optJSONObject("data") ?: JSONObject()
        val messageId = message.optString("messageId")

        when (command) {
            CMD_FXA_STATUS -> reply(
                messageId,
                CMD_FXA_STATUS,
                """{"capabilities":{},"signedInUser":null}""",
            )
            CMD_CAN_LINK_ACCOUNT -> reply(
                messageId,
                CMD_CAN_LINK_ACCOUNT,
                """{"ok":true}""",
            )
            CMD_LOGIN, "fxaccounts:oauth_login" -> {
                val token = data.optString("sessionToken", "").trim()
                    .ifBlank { data.optString("token", "").trim() }
                if (SESSION_TOKEN_HEX.matches(token)) {
                    pendingSessionToken = token
                    val isVerified = if (data.has("verified")) data.optBoolean("verified", true) else true
                    if (isVerified) {
                        notifySessionToken(token)
                    }
                }
                if (messageId.isNotBlank()) {
                    reply(messageId, command, """{"ok":true}""")
                }
            }
            else -> {
                if (command.contains("verified", ignoreCase = true)) {
                    val token = data.optString("sessionToken", "").trim()
                        .ifBlank { data.optString("token", "").trim() }
                        .ifBlank { pendingSessionToken ?: "" }
                    if (SESSION_TOKEN_HEX.matches(token)) {
                        pendingSessionToken = token
                        notifySessionToken(token)
                    }
                }
                if (messageId.isNotBlank()) {
                    reply(messageId, command, """{"ok":true}""")
                }
            }
        }
    }

    fun notifySessionToken(token: String) {
        if (!SESSION_TOKEN_HEX.matches(token)) return
        val view = webViewRef.get()
        if (view != null) {
            view.post { onSessionToken(token) }
        } else {
            onSessionToken(token)
        }
    }

    private fun reply(messageId: String, command: String, dataJson: String) {
        val view = webViewRef.get() ?: return
        val script = "window.__foxyReply && window.__foxyReply(" +
            "${jsString(FXA_CHANNEL_ID)}, ${jsString(messageId)}, ${jsString(command)}, ${jsString(dataJson)});"
        view.post {
            runCatching { view.evaluateJavascript(script, null) }
                .onFailure { AppLogger.w(TAG, "could not answer webchannel command $command", it) }
        }
    }
}

private fun isSuccessUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val u = url.lowercase()
    return u.contains("/signin/confirmed") ||
        u.contains("/connect_another_device") ||
        u.contains("/pair") ||
        u.contains("/settings") ||
        u.contains("/complete") ||
        u.contains("success")
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
        } else if (!busy) {
            currentOnCanceled()
        }
    }

    fun handleSession(token: String) {
        if (busy) return
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
                    } else if (!busy) {
                        currentOnCanceled()
                    }
                },
                enabled = !busy,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        settings.userAgentString = FIREFOX_ANDROID_UA
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
                                val host = request.url.host?.lowercase().orEmpty()
                                if (host == "accounts.firefox.com" || host.endsWith(".firefox.com")) {
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

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                if (isSuccessUrl(url)) {
                                    bridge.onSessionVerified()
                                }
                            }

                            override fun onPageStarted(
                                view: WebView,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                pageLoaded = false
                                if (isSuccessUrl(url)) {
                                    bridge.onSessionVerified()
                                }
                                runCatching { view.evaluateJavascript(WEBCHANNEL_HOOK_JS, null) }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                pageLoaded = true
                                pageTitle = view.title.orEmpty()
                                if (isSuccessUrl(url)) {
                                    bridge.onSessionVerified()
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
