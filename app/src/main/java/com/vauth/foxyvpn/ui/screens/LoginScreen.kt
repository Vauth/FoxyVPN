package com.vauth.foxyvpn.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.FxaAuthRepository
import com.vauth.foxyvpn.data.ThemeMode
import com.vauth.foxyvpn.ui.LoginViewModel
import com.vauth.foxyvpn.ui.theme.ThemeController

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(
    authRepository: FxaAuthRepository,
    themeController: ThemeController? = null,
    onSignedIn: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val model: LoginViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory = remember(authRepository) { LoginViewModel.factory(authRepository) }
        )
    val uiState by model.uiState.collectAsStateWithLifecycle()
    val browserStartUrl = uiState.browserStartUrl
    val completingBrowserLogin = uiState.isLoading
    val errorMessage = uiState.errorMessage
    val storageError by authRepository.storageError.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val systemInDarkTheme = isSystemInDarkTheme()
    androidx.compose.runtime.LaunchedEffect(uiState.signedIn) { if (uiState.signedIn) onSignedIn() }

    if (browserStartUrl != null) {
        BrowserLoginScreen(
            startUrl = browserStartUrl,
            busy = completingBrowserLogin,
            errorMessage = errorMessage,
            onSessionCaptured = model::completeBrowserLogin,
            onCanceled = model::cancelVerification,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (themeController != null) {
                        val mode = themeController.mode
                        val showingDark = themeController.resolveDark(systemInDarkTheme)
                        Box(
                            modifier =
                                Modifier.size(48.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        role = Role.Button,
                                        onClick = { themeController.toggle(systemInDarkTheme) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            themeController.set(ThemeMode.SYSTEM)
                                        },
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector =
                                    when (mode) {
                                        ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                                        ThemeMode.LIGHT -> Icons.Filled.LightMode
                                        ThemeMode.DARK -> Icons.Filled.DarkMode
                                    },
                                contentDescription =
                                    when (mode) {
                                        ThemeMode.SYSTEM ->
                                            if (showingDark) {
                                                stringResource(R.string.theme_desc_system_light)
                                            } else {
                                                stringResource(R.string.theme_desc_system_dark)
                                            }
                                        ThemeMode.LIGHT ->
                                            stringResource(R.string.theme_desc_light)
                                        ThemeMode.DARK ->
                                            stringResource(R.string.theme_desc_dark)
                                    },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
                    .imePadding(),
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
}
