package com.vauth.foxyvpn.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
import java.lang.ref.WeakReference
import org.json.JSONObject

private const val TAG = "BrowserLogin"

/**
 * The WebChannel id the accounts.firefox.com content server always communicates
 * on, and the commands this flow must understand. Message envelopes look like
 * `{id: "account_updates", message: {command, data, messageId}}`; page -> app
 * traffic is a `WebChannelMessageToChrome` CustomEvent whose detail is a JSON
 * string, and app -> page replies go out as `WebChannelMessageToContent`.
 */
private const val FXA_CHANNEL_ID = "account_updates"
private const val CMD_FXA_STATUS = "fxaccounts:fxa_status"
private const val CMD_CAN_LINK_ACCOUNT = "fxaccounts:can_link_account"
private const val CMD_LOGIN = "fxaccounts:login"
private const val CMD_OAUTH_LOGIN = "fxaccounts:oauth_login"

private val SESSION_TOKEN_HEX = Regex("^[0-9a-f]{64}$")

/**
 * accounts.firefox.com adjusts some of its behaviour based on the browser; a
 * plain WebView UA gets "please use Firefox" style treatment and can suppress
 * the WebChannel handshake. Identifying as mobile Firefox keeps the flow the
 * same one Firefox for Android users see.
 */
private const val FIREFOX_ANDROID_UA =
    "Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0"

private fun jsString(value: String): String = JSONObject.quote(value)

/**
 * Installed on every page load. Mirrors the WebChannel bridge that Firefox
 * itself provides: it forwards every WebChannelMessageToChrome event to the
 * app (foxyWebChannel.onMessage) and exposes a __foxyReply helper that emits
 * correctly formatted WebChannelMessageToContent envelopes for the commands
 * that expect an answer.
 */
private val WEBCHANNEL_HOOK_JS = """
(function() {
  if (window.__foxyChannelHooked) return;
  window.__foxyChannelHooked = true;
  var forward = function (detail) {
    try {
      foxyWebChannel.onMessage(typeof detail === 'string' ? detail : JSON.stringify(detail));
    } catch (e) {}
  };
  window.addEventListener('WebChannelMessageToChrome', function (e) { forward(e.detail); });
  document.addEventListener('WebChannelMessageToChrome', function (e) { forward(e.detail); });
  window.__foxyReply = function (channelId, messageId, command, dataJson) {
    var envelope = {
      id: channelId,
      message: { messageId: messageId, command: command, data: JSON.parse(dataJson) }
    };
    window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: JSON.stringify(envelope) }));
  };
})();
""".trimIndent()

/**
 * Receives the WebChannel traffic forwarded by the injected hook and answers
 * the two commands the sign-in flow stalls without:
 *  - fxaccounts:fxa_status  -> capabilities + no signed-in user
 *  - fxaccounts:can_link_account -> {ok: true}
 * and captures the account's session token from fxaccounts:login, which is
 * the payload Firefox itself uses to sign the account in.
 */
private class WebChannelBridge(
    webView: WebView,
    private val onSessionToken: (String) -> Unit,
) {
    private val webViewRef = WeakReference(webView)

    @JavascriptInterface
    fun onMessage(payload: String) {
        val message = runCatching {
            JSONObject(payload).optJSONObject("message") ?: JSONObject()
        }.getOrNull() ?: return
        val command = message.optString("command")
        val data = message.optJSONObject("data") ?: JSONObject()
        val messageId = message.optString("messageId")
        AppLogger.i(TAG, "webchannel: $command")

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
            CMD_LOGIN -> {
                val token = data.optString("sessionToken", "").trim()
                val email = data.optString("email", "")
                if (SESSION_TOKEN_HEX.matches(token)) {
                    AppLogger.i(TAG, "webchannel: session token received" + if (email.isBlank()) "" else " for $email")
                    onSessionToken(token)
                } else {
                    AppLogger.w(
                        TAG,
                        "webchannel: login event carried no usable session token " +
                            "(verified=${data.optBoolean("verified", false)})",
                    )
                }
            }
            CMD_OAUTH_LOGIN -> AppLogger.w(
                TAG,
                "webchannel: oauth_login received but OAuth codes cannot be redeemed by this app",
            )
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

/**
 * In-app browser sign-in. Loads the accounts.firefox.com sync sign-in page; the
 * page delivers the account session token to the "browser" (this WebView) over
 * the WebChannel bridge once the user has signed in, and the app mints an FxA
 * OAuth token from it (fxa-credentials grant) — the only token type Guardian's
 * FPN proxy endpoints accept.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserLoginScreen(
    startUrl: String,
    busy: Boolean,
    errorMessage: String? = null,
    onSessionCaptured: (String) -> Unit,
    onCanceled: () -> Unit,
) {
    var capturedToken by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var pageProgress by remember { mutableIntStateOf(0) }
    var pageLoaded by remember { mutableStateOf(false) }
    val currentOnSession by rememberUpdatedState(onSessionCaptured)
    val currentOnCanceled by rememberUpdatedState(onCanceled)

    BackHandler(enabled = true) {
        if (!busy) currentOnCanceled()
    }

    fun handleSession(token: String) {
        if (busy) return
        if (capturedToken == token) return
        capturedToken = token
        currentOnSession(token)
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString = FIREFOX_ANDROID_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(
                            WebChannelBridge(this, ::handleSession),
                            "foxyWebChannel",
                        )
                        webViewClient = object : WebViewClient() {
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
                                runCatching { view.evaluateJavascript(WEBCHANNEL_HOOK_JS, null) }
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
