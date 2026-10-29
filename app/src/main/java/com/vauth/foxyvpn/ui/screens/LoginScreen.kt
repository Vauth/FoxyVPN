package com.vauth.foxyvpn.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.data.FxaAuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authRepository: FxaAuthRepository,
    onSignedIn: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var awaitingTwoFactor by rememberSaveable { mutableStateOf(authRepository.pendingSessionToken != null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showLegacyLogin by rememberSaveable { mutableStateOf(false) }
    var browserStartUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var browserVerifier by rememberSaveable { mutableStateOf<String?>(null) }
    var completingBrowserLogin by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = awaitingTwoFactor) {
        authRepository.clearPendingSession()
        awaitingTwoFactor = false
        errorMessage = null
        code = ""
    }

    fun startBrowserLogin() {
        if (completingBrowserLogin || isLoading) return
        errorMessage = null
        isLoading = true
        scope.launch {
            // The flow must be started by the app's own HTTP client so Guardian's
            // flow session cookie lands in the shared cookie jar used by the final
            // /login/verify call (the official client does the same).
            authRepository.startBrowserLoginFlow()
                .onSuccess { flow ->
                    browserStartUrl = flow.startUrl
                    browserVerifier = flow.codeVerifier
                }
                .onFailure { errorMessage = it.message ?: "Could not start the browser sign-in" }
            isLoading = false
        }
    }

    fun handleBrowserCode(authCode: String) {
        val verifier = browserVerifier ?: return
        if (completingBrowserLogin) return
        completingBrowserLogin = true
        errorMessage = null
        scope.launch {
            val result = authRepository.completeBrowserLogin(authCode, verifier)
            completingBrowserLogin = false
            result.onSuccess {
                browserStartUrl = null
                browserVerifier = null
                onSignedIn()
            }.onFailure {
                // Captured codes are single-use; drop the flow so the next attempt
                // starts fresh with a new PKCE pair.
                browserStartUrl = null
                browserVerifier = null
                errorMessage = it.message ?: "Browser sign-in failed"
            }
        }
    }

    fun doSubmitLogin() {
        val cleanEmail = FxaAuthRepository.normalizeEmail(email)
        if (cleanEmail.isBlank() || password.isBlank() || isLoading) return
        errorMessage = null
        isLoading = true
        scope.launch {
            val result = authRepository.startLogin(cleanEmail, password)
            isLoading = false
            result.onSuccess { needsVerification ->
                if (needsVerification) awaitingTwoFactor = true else onSignedIn()
            }.onFailure { errorMessage = it.message ?: "Sign-in failed" }
        }
    }

    fun doSubmitTwoFactor() {
        val cleanCode = FxaAuthRepository.sanitizeCode(code)
        if (cleanCode.isBlank() || isLoading) return
        errorMessage = null
        isLoading = true
        scope.launch {
            val result = authRepository.submitTwoFactorCode(cleanCode)
            isLoading = false
            result.onSuccess { onSignedIn() }
                .onFailure {
                    errorMessage = it.message ?: "Verification failed"
                    if (authRepository.pendingSessionToken == null) {
                        awaitingTwoFactor = false
                    }
                }
        }
    }

    if (browserStartUrl != null && browserVerifier != null) {
        BrowserLoginScreen(
            startUrl = browserStartUrl!!,
            busy = completingBrowserLogin,
            onCodeCaptured = ::handleBrowserCode,
            onCanceled = {
                browserStartUrl = null
                browserVerifier = null
                completingBrowserLogin = false
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
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                "FoxyVPN runs on the free 50 GB of monthly VPN traffic Mozilla includes with a Firefox account. No subscription is required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 24.dp))

            if (!awaitingTwoFactor) {
                Button(
                    onClick = { startBrowserLogin() },
                    enabled = !completingBrowserLogin && !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (completingBrowserLogin) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Sign in with browser")
                    }
                }
                Text(
                    "Recommended: opens the official Mozilla sign-in page in an in-app browser, which avoids the HTTP 406 bot-protection error.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.padding(top = 20.dp))
                HorizontalDivider()
                Spacer(Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = { showLegacyLogin = !showLegacyLogin },
                    enabled = !completingBrowserLogin,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showLegacyLogin) "Hide email & password sign-in" else "Use email & password instead")
                }
            }

            if (showLegacyLogin && !awaitingTwoFactor) {
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it.replace(Regex("[\\p{Z}\\u200B-\\u200D\\uFEFF\\u00A0\\s]+"), "").lowercase()
                    },
                    label = { Text("Firefox account email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            doSubmitLogin()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 20.dp))
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        doSubmitLogin()
                    },
                    enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Continue")
                    }
                }
            } else if (awaitingTwoFactor) {
                Text("Enter the confirmation code sent to your email", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.replace(Regex("[\\p{Z}\\u200B-\\u200D\\uFEFF\\u00A0\\s]+"), "")
                    },
                    label = { Text("Confirmation code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            doSubmitTwoFactor()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 20.dp))
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        doSubmitTwoFactor()
                    },
                    enabled = !isLoading && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Verify")
                    }
                }
            }

            errorMessage?.let {
                Spacer(Modifier.padding(top = 12.dp))
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
