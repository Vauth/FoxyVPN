package com.vauth.foxyvpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.FoxyVpnApp
import com.vauth.foxyvpn.data.model.ConnectionState
import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.ui.theme.LocalFoxyStatusColors
import com.vauth.foxyvpn.ui.theme.ThemeController
import com.vauth.foxyvpn.ui.theme.ThemeMode
import com.vauth.foxyvpn.vpn.FoxyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    app: FoxyVpnApp,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by FoxyVpnService.state.collectAsState()
    val lastError by FoxyVpnService.lastError.collectAsState()

    val selectedProxy by produceState<ProxyCandidate?>(initialValue = null, state) {
        value = withContext(Dispatchers.IO) { app.proxyStateStore.load() }
    }

    val statusColors = LocalFoxyStatusColors.current
    val targetRingColor = when (state) {
        ConnectionState.CONNECTED -> statusColors.connected
        ConnectionState.CONNECTING -> statusColors.connecting
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }

    val ringColor by animateColorAsState(targetValue = targetRingColor, label = "connect-ring-color")
    val scale by animateFloatAsState(
        targetValue = if (state == ConnectionState.CONNECTED) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "connect-scale",
    )

    val systemInDarkTheme = isSystemInDarkTheme()
    val haptics = LocalHapticFeedback.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FoxyVPN") },
                actions = {

                    val mode = themeController.mode
                    val showingDark = themeController.resolveDark(systemInDarkTheme)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
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
                            imageVector = when (mode) {
                                ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                                ThemeMode.LIGHT -> Icons.Filled.LightMode
                                ThemeMode.DARK -> Icons.Filled.DarkMode
                            },

                            contentDescription = when (mode) {
                                ThemeMode.SYSTEM ->
                                    "Theme: follow system. Tap to switch to ${if (showingDark) "light" else "dark"} mode"
                                ThemeMode.LIGHT -> "Theme: light. Tap for dark mode, long press to follow the system"
                                ThemeMode.DARK -> "Theme: dark. Tap for light mode, long press to follow the system"
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.15f))
                    .clickable {

                        if (state == ConnectionState.DISCONNECTED) onRequestConnect() else onDisconnect()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size((140 * scale).dp)
                        .clip(CircleShape)
                        .background(ringColor.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Power,
                        contentDescription = if (state == ConnectionState.DISCONNECTED) "Connect" else "Disconnect",
                        tint = ringColor,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }

            Spacer(Modifier.padding(top = 24.dp))
            Text(
                text = when (state) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.CONNECTING -> "Connecting\u2026"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                },
                style = MaterialTheme.typography.headlineSmall,
            )

            if (state == ConnectionState.CONNECTING) {
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "Tap to cancel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val error = lastError
            if (error != null && state != ConnectionState.CONNECTING) {
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.padding(top = 24.dp))
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServers),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Location",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Filled.Public, contentDescription = null)
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(
                            selectedProxy?.let { it.countryName.ifBlank { it.countryCode } } ?: "Recommended",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Spacer(Modifier.padding(top = 12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onOpenSettings).padding(8.dp),
            )
        }
    }
}
