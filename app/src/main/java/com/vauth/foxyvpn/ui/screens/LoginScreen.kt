package com.vauth.foxyvpn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.FxaAuthRepository
import com.vauth.foxyvpn.ui.LoginViewModel

@Composable
fun LoginScreen(authRepository: FxaAuthRepository, onSignedIn: () -> Unit) {
    val model: LoginViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory = remember(authRepository) { LoginViewModel.factory(authRepository) }
        )
    val uiState by model.uiState.collectAsStateWithLifecycle()
    val browserStartUrl = uiState.browserStartUrl
    val completingBrowserLogin = uiState.isLoading
    val errorMessage = uiState.errorMessage
    val storageError by authRepository.storageError.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(uiState.signedIn) { if (uiState.signedIn) onSignedIn() }

    if (browserStartUrl != null) {
        BrowserLoginScreen(
            startUrl = browserStartUrl,
            busy = completingBrowserLogin,
            authRepository = authRepository,
            errorMessage = errorMessage,
            onTokensCaptured = model::completeBrowserLoginWithTokens,
            onSessionCaptured = model::completeBrowserLogin,
            onCanceled = model::cancelVerification,
        )
        return
    }

    Box(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier.widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.login_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            storageError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = model::startBrowserLogin,
                enabled = !completingBrowserLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.login_btn_sign_in))
            }
            Text(
                stringResource(R.string.login_help),
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}
