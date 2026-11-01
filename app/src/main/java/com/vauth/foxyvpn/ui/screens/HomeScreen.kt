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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.ProxyStateStore
import com.vauth.foxyvpn.data.ThemeMode
import com.vauth.foxyvpn.data.formatBytes
import com.vauth.foxyvpn.data.model.ConnectionState
import com.vauth.foxyvpn.data.model.displayName
import com.vauth.foxyvpn.data.model.emojiFlag
import com.vauth.foxyvpn.ui.HomeViewModel
import com.vauth.foxyvpn.ui.theme.LocalFoxyStatusColors
import com.vauth.foxyvpn.ui.theme.ThemeController
import com.vauth.foxyvpn.vpn.FoxyVpnService
import com.vauth.foxyvpn.vpn.IpLocationInfo
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    proxyStateStore: ProxyStateStore,
    homeModel: HomeViewModel,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by FoxyVpnService.state.collectAsStateWithLifecycle()
    val proxyOnlyActive by FoxyVpnService.proxyOnlyActive.collectAsStateWithLifecycle()
    val lastError by FoxyVpnService.lastError.collectAsStateWithLifecycle()
    val liveStats by FoxyVpnService.vpnLiveStats.collectAsStateWithLifecycle()
    val connectedAtElapsedMs by FoxyVpnService.connectedAtElapsedMs.collectAsStateWithLifecycle()
    val pingLatency by FoxyVpnService.pingLatencyMs.collectAsStateWithLifecycle()
    val vpnEdgeIp by FoxyVpnService.vpnEdgeIp.collectAsStateWithLifecycle()
    val isPinging by homeModel.isPinging.collectAsStateWithLifecycle()

    val selectedProxy by proxyStateStore.selectedProxyFlow.collectAsStateWithLifecycle()
    val activeProxy by FoxyVpnService.activeCandidate.collectAsStateWithLifecycle()
    val displayProxy =
        if (state == ConnectionState.DISCONNECTED) selectedProxy else activeProxy ?: selectedProxy

    val statusColors = LocalFoxyStatusColors.current
    val targetRingColor =
        when (state) {
            ConnectionState.CONNECTED -> statusColors.connected
            ConnectionState.CONNECTING -> statusColors.connecting
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
        }

    val ringColor by
        animateColorAsState(targetValue = targetRingColor, label = "connect-ring-color")
    val scale by
        animateFloatAsState(
            targetValue = if (state == ConnectionState.CONNECTED) 1f else 0.92f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "connect-scale",
        )

    val systemInDarkTheme = isSystemInDarkTheme()
    val haptics = LocalHapticFeedback.current

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    val account by homeModel.account.collectAsStateWithLifecycle()
    val realIp = account.realIp?.let { IpLocationInfo(it, account.country.orEmpty()) }
    val quotaRemaining = account.quotaRemaining
    val quotaMax = account.quotaMax
    val isUnlimited = account.unlimited

    LaunchedEffect(Unit) {
        homeModel.refreshAccount()
    }

    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED) {
            homeModel.refreshAccount()
        }
    }

    LaunchedEffect(state, connectedAtElapsedMs) {
        val startEpoch = connectedAtElapsedMs
        if (state == ConnectionState.CONNECTED && startEpoch != null) {
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                elapsedSeconds = ((now - startEpoch) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
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
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val scrollState = rememberScrollState()
            val heroGap = 18.dp
            val naturalContentHeight = 566.dp
            val availableExtra = (maxHeight - naturalContentHeight).coerceAtLeast(0.dp)
            val dynamicSpace = if (availableExtra > 0.dp) (availableExtra / 2) else 16.dp

            Column(
                modifier =
                    Modifier.widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .verticalScroll(scrollState, enabled = scrollState.maxValue > 0)
                        .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(dynamicSpace))

                Box(
                    modifier =
                        Modifier.size(180.dp)
                            .clip(CircleShape)
                            .background(ringColor.copy(alpha = 0.15f))
                            .clickable(role = Role.Button) {
                                if (state == ConnectionState.DISCONNECTED) onRequestConnect()
                                else onDisconnect()
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier.size((140 * scale).dp)
                                .clip(CircleShape)
                                .background(ringColor.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Power,
                            contentDescription =
                                if (state == ConnectionState.DISCONNECTED) "Connect"
                                else "Disconnect",
                            tint = ringColor,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }

                Spacer(Modifier.height(heroGap))

                Text(
                    text =
                        when (state) {
                            ConnectionState.CONNECTED -> "Connected"
                            ConnectionState.CONNECTING -> "Connecting\u2026"
                            ConnectionState.DISCONNECTED -> "Disconnected"
                        },
                    style =
                        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )

                Spacer(Modifier.height(4.dp))

                if (state == ConnectionState.CONNECTING) {
                    Text(
                        if (lastError != null) "Retrying connection\u2026 Tap to cancel" else "Tap to cancel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text =
                            if (state == ConnectionState.CONNECTED) formatDuration(elapsedSeconds)
                            else "00:00:00",
                        style =
                            MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val error = lastError
                if (error != null && state != ConnectionState.CONNECTING) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }

                Spacer(Modifier.height(heroGap))

                Card(
                    onClick = onOpenServers,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Location",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(top = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val flag = displayProxy?.emojiFlag
                            if (!flag.isNullOrBlank()) {
                                Text(flag, style = MaterialTheme.typography.titleMedium)
                            } else {
                                Icon(imageVector = Icons.Filled.Public, contentDescription = null)
                            }
                            Spacer(Modifier.padding(start = 8.dp))
                            val displayName =
                                displayProxy?.displayName?.ifBlank { displayProxy.countryCode }
                                    ?: stringResource(R.string.home_recommended)
                            Text(
                                displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (
                            state != ConnectionState.DISCONNECTED &&
                                activeProxy != null &&
                                selectedProxy != null &&
                                activeProxy != selectedProxy
                        ) {
                            val nextDisplayName =
                                selectedProxy?.displayName?.ifBlank { selectedProxy?.countryCode.orEmpty() }.orEmpty()
                            Text(
                                stringResource(R.string.home_next_connection, nextDisplayName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val isConnected = state == ConnectionState.CONNECTED
                        val ipDisplay =
                            if (isConnected) {
                                vpnEdgeIp?.let {
                                    if (it.countryCode.isNotEmpty()) "${it.ip} • ${it.countryCode}"
                                    else it.ip
                                } ?: "\u2014"
                            } else {
                                realIp?.let {
                                    if (it.countryCode.isNotEmpty()) "${it.ip} • ${it.countryCode}"
                                    else it.ip
                                } ?: "\u2014"
                            }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Current IP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    ipDisplay,
                                    style =
                                        MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            val badgeColor =
                                if (isConnected) statusColors.connected
                                else MaterialTheme.colorScheme.error
                            val badgeBg = badgeColor.copy(alpha = 0.15f)
                            Box(
                                modifier =
                                    Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(badgeBg)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (isConnected) {
                                        if (proxyOnlyActive) "Proxy active" else "Protected"
                                    } else "Exposed",
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                    color = badgeColor,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val isConnected = state == ConnectionState.CONNECTED
                        val remaining = quotaRemaining
                        val max = quotaMax

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Data Used",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (isConnected) formatBytes(liveStats.sessionBytes)
                                    else "0.0 MB",
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Column(
                                modifier =
                                    Modifier.weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            enabled = isConnected && !isPinging,
                                            role = Role.Button,
                                        ) {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            homeModel.triggerPing()
                                        }
                            ) {
                                Text(
                                    stringResource(R.string.home_latency),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                val currentPing = if (isConnected) pingLatency else null
                                Text(
                                    text =
                                        when {
                                            isPinging -> stringResource(R.string.home_pinging)
                                            currentPing != null -> "$currentPing ms"
                                            else -> "\u2014 ms"
                                        },
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                    color =
                                        if (currentPing != null) statusColors.connected
                                        else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Monthly Quota Remaining",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            val quotaText =
                                when {
                                    remaining != null && max != null ->
                                        "${formatBytes(remaining)} / ${formatBytes(max)}"
                                    remaining != null -> formatBytes(remaining)
                                    isUnlimited -> "Unlimited"
                                    max != null -> "Limited to ${formatBytes(max)}"
                                    else -> "\u2014"
                                }
                            Text(
                                quotaText,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        val quotaFraction =
                            when {
                                max != null && max > 0L && remaining != null ->
                                    (remaining.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                                isUnlimited -> 1f
                                remaining != null && remaining > 0L -> 1f
                                else -> 0f
                            }

                        LinearProgressIndicator(
                            progress = { quotaFraction },
                            modifier =
                                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color =
                                if (isConnected) statusColors.connected
                                else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }

                Spacer(Modifier.height(dynamicSpace))
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
