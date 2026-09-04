package com.vauth.foxyvpn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.data.FxaAuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authRepository: FxaAuthRepository,
    onSignedIn: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var awaitingTwoFactor by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Firefox account email") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(top = 20.dp))
            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    scope.launch {
                        val result = authRepository.startLogin(email, password)
                        isLoading = false
                        result.onSuccess { needsVerification ->
                            if (needsVerification) awaitingTwoFactor = true else onSignedIn()
                        }.onFailure { errorMessage = it.message ?: "Sign-in failed" }
                    }
                },
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp)) else Text("Continue")
            }
        } else {
            Text("Enter the confirmation code sent to your email", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Confirmation code") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(top = 20.dp))
            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    scope.launch {
                        val result = authRepository.submitTwoFactorCode(code)
                        isLoading = false
                        result.onSuccess { onSignedIn() }
                            .onFailure { errorMessage = it.message ?: "Verification failed" }
                    }
                },
                enabled = !isLoading && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp)) else Text("Verify")
            }
        }

        errorMessage?.let {
            Spacer(Modifier.padding(top = 12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
