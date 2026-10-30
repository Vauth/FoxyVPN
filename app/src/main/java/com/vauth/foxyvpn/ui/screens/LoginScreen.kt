package com.vauth.foxyvpn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.data.FxaAuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authRepository: FxaAuthRepository,
    onSignedIn: () -> Unit,
) {
    var browserStartUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var completingBrowserLogin by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun startBrowserLogin() {
        if (completingBrowserLogin) return
        errorMessage = null
        browserStartUrl = authRepository.getBrowserLoginUrl()
    }

    fun handleBrowserSession(sessionToken: String) {
        if (completingBrowserLogin) return
        completingBrowserLogin = true
        errorMessage = null
        scope.launch {
            val result = authRepository.completeBrowserLogin(sessionToken)
            completingBrowserLogin = false
            result.onSuccess {
                browserStartUrl = null
                errorMessage = null
                onSignedIn()
            }.onFailure {
                errorMessage = it.message ?: "Browser sign-in failed. Please try again."
            }
        }
    }

    if (browserStartUrl != null) {
        BrowserLoginScreen(
            startUrl = browserStartUrl!!,
            busy = completingBrowserLogin,
            errorMessage = errorMessage,
            onSessionCaptured = ::handleBrowserSession,
            onCanceled = {
                browserStartUrl = null
                completingBrowserLogin = false
                errorMessage = null
            },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Sign in with Firefox", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "FoxyVPN runs on the free 50 GB of monthly VPN traffic Mozilla includes with a Firefox account. No subscription is required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { startBrowserLogin() },
                enabled = !completingBrowserLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign in with Firefox")
            }
            Text(
                "Opens the official Mozilla sign-in page in an in-app browser and returns automatically once you are signed in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}
