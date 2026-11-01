package com.vauth.foxyvpn.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vauth.foxyvpn.BuildConfig
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.ProxySettingsRepository
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.ui.ProxyEditor
import com.vauth.foxyvpn.ui.ProxySettingsViewModel
import com.vauth.foxyvpn.ui.settings.ConcurrencyPickerDialog
import com.vauth.foxyvpn.ui.settings.CustomEdgeAddressDialog
import com.vauth.foxyvpn.ui.settings.DnsProviderPickerDialog
import com.vauth.foxyvpn.ui.settings.HttpVersionPickerDialog
import com.vauth.foxyvpn.ui.settings.LatencyProbeDialog
import com.vauth.foxyvpn.ui.settings.MtuPickerDialog
import com.vauth.foxyvpn.ui.settings.ProxySettingsDialog
import com.vauth.foxyvpn.ui.settings.SplitTunnelDialog
import com.vauth.foxyvpn.ui.settings.TlsFragmentPickerDialog
import com.vauth.foxyvpn.ui.settings.UtlsFingerprintPickerDialog
import com.vauth.foxyvpn.vpn.FoxyVpnService
import com.vauth.foxyvpn.vpn.TunnelHealth

private const val GITHUB_URL = com.vauth.foxyvpn.data.AppLinks.SOURCE

private enum class SettingsDialog {
    NONE,
    LATENCY_PROBE,
    TLS_FRAGMENT,
    UTLS_FINGERPRINT,
    CONCURRENCY,
    MTU,
    DNS_PROVIDER,
    EDGE_ADDRESS,
    SPLIT_TUNNEL,
    HTTP_VERSION,
}

@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onOpenLogs: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    signOutInProgress: Boolean = false,
    onCheckForUpdates: (() -> Unit)? = null,
    automaticUpdates: Boolean = false,
    onAutomaticUpdatesChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    var automaticRecovery by remember { mutableStateOf(settingsStore.automaticRecovery) }
    val health by FoxyVpnService.tunnelHealth.collectAsStateWithLifecycle()
    var dnsProvider by remember { mutableStateOf(settingsStore.dnsProvider) }
    var customDohUrl by remember { mutableStateOf(settingsStore.customDohUrl) }
    var proxyOnlyMode by remember { mutableStateOf(settingsStore.proxyOnlyMode) }
    var customEdgeAddress by remember { mutableStateOf(settingsStore.customEdgeAddress) }
    var excludedApps by remember { mutableStateOf(settingsStore.excludedApps) }
    var includedApps by remember { mutableStateOf(settingsStore.includedApps) }
    var bypassLocalNetwork by remember { mutableStateOf(settingsStore.bypassLocalNetwork) }
    var splitTunnelMode by remember { mutableStateOf(settingsStore.splitTunnelMode) }
    var mtu by remember { mutableIntStateOf(settingsStore.mtu) }
    var latencyProbeUrl by remember { mutableStateOf(settingsStore.latencyProbeUrl) }
    var httpVersion by remember { mutableStateOf(settingsStore.httpVersion) }
    var tlsFragmentMode by remember { mutableStateOf(settingsStore.tlsFragmentMode) }
    var fragmentFallbackDelay by remember { mutableIntStateOf(settingsStore.fragmentFallbackDelay) }
    var utlsFingerprint by remember { mutableStateOf(settingsStore.utlsFingerprint) }
    var tcpFastOpen by remember { mutableStateOf(settingsStore.tcpFastOpen) }
    var maxConcurrentStreams by remember { mutableIntStateOf(settingsStore.maxConcurrentStreams) }
    var ipv6Enabled by remember { mutableStateOf(settingsStore.ipv6Enabled) }
    var activeDialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }

    val proxyViewModel: ProxySettingsViewModel =
        viewModel(
            factory =
                remember(settingsStore) {
                    ProxySettingsViewModel.factory(ProxySettingsRepository(settingsStore))
                }
        )
    val proxyState by proxyViewModel.state.collectAsStateWithLifecycle()
    ProxySettingsDialog(
        proxyState,
        proxyViewModel::close,
        proxyViewModel::open,
        proxyViewModel::save,
        proxyViewModel::save,
    )

    when (activeDialog) {
        SettingsDialog.NONE -> {}
        SettingsDialog.LATENCY_PROBE -> LatencyProbeDialog(
            current = latencyProbeUrl,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = {
                settingsStore.latencyProbeUrl = it
                latencyProbeUrl = it
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.TLS_FRAGMENT -> TlsFragmentPickerDialog(
            currentMode = tlsFragmentMode,
            currentDelay = fragmentFallbackDelay,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = { mode, delay ->
                tlsFragmentMode = mode
                settingsStore.tlsFragmentMode = mode
                fragmentFallbackDelay = delay
                settingsStore.fragmentFallbackDelay = delay
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.UTLS_FINGERPRINT -> UtlsFingerprintPickerDialog(
            current = utlsFingerprint,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = {
                utlsFingerprint = it
                settingsStore.utlsFingerprint = it
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.CONCURRENCY -> ConcurrencyPickerDialog(
            current = maxConcurrentStreams,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = {
                maxConcurrentStreams = it
                settingsStore.maxConcurrentStreams = it
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.MTU -> MtuPickerDialog(
            current = mtu,
            minimum = if (ipv6Enabled) 1280 else SettingsStore.MIN_MTU,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = {
                mtu = it
                settingsStore.mtu = it
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.DNS_PROVIDER -> DnsProviderPickerDialog(
            currentProvider = dnsProvider,
            currentCustomUrl = customDohUrl,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = { provider, url ->
                settingsStore.saveDns(provider, url)
                dnsProvider = provider
                customDohUrl = settingsStore.customDohUrl
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.EDGE_ADDRESS -> CustomEdgeAddressDialog(
            current = customEdgeAddress,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = {
                customEdgeAddress = it
                settingsStore.customEdgeAddress = it
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.SPLIT_TUNNEL -> SplitTunnelDialog(
            currentMode = splitTunnelMode,
            currentExcluded = excludedApps,
            currentIncluded = includedApps,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = { mode, excluded, included ->
                settingsStore.saveSplitTunneling(mode, excluded, included)
                splitTunnelMode = mode
                excludedApps = excluded
                includedApps = included
                activeDialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.HTTP_VERSION -> HttpVersionPickerDialog(
            current = httpVersion,
            onDismiss = { activeDialog = SettingsDialog.NONE },
            onConfirm = {
                httpVersion = it
                settingsStore.httpVersion = it
                activeDialog = SettingsDialog.NONE
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionLabel(stringResource(R.string.settings_section_connection))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_always_on_vpn)) },
                supportingContent = { Text(stringResource(R.string.settings_always_on_help)) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                )
                            }
                            .onFailure {
                                android.widget.Toast.makeText(
                                        context,
                                        "VPN settings are unavailable on this device",
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_latency_test_url)) },
                supportingContent = { Text(latencyProbeUrl) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.LATENCY_PROBE },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_tunnel_mtu)) },
                supportingContent = { Text(mtu.toString()) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.MTU },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_section_dns))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_dns_provider)) },
                supportingContent = {
                    val subtitle =
                        when (dnsProvider) {
                            SettingsStore.DnsProvider.CUSTOM ->
                                customDohUrl.ifBlank { "Custom DoH" }
                            else -> dnsProvider.label
                        }
                    Text(subtitle)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.DNS_PROVIDER },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.proxy))
            SettingsToggle(
                title = stringResource(R.string.settings_proxy_only_mode),
                summary = stringResource(R.string.settings_proxy_only_help),
                checked = proxyOnlyMode,
                onCheckedChange = {
                    settingsStore.proxyOnlyMode = it
                    proxyOnlyMode = it
                },
            )
            if (proxyState.editor == null && proxyState.error != null) {
                Text(
                    proxyState.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TextButton(onClick = proxyViewModel::reload) {
                    Text(stringResource(R.string.retry))
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.local_proxy)) },
                supportingContent = {
                    val summary = proxyState.summary
                    Text(
                        if (summary == null) stringResource(R.string.loading)
                        else
                            stringResource(
                                R.string.local_proxy_summary,
                                SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS,
                                summary.localPort,
                            )
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier =
                    Modifier.clickable(enabled = !proxyState.busy && proxyState.summary != null) {
                        proxyViewModel.open(ProxyEditor.LOCAL)
                    },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_section_split_tunnel))
            SettingsToggle(
                title = stringResource(R.string.settings_bypass_local_network),
                summary = stringResource(R.string.settings_bypass_local_network_help),
                checked = bypassLocalNetwork,
                enabled = !proxyOnlyMode,
                onCheckedChange = {
                    settingsStore.bypassLocalNetwork = it
                    bypassLocalNetwork = it
                },
            )
            ListItem(
                headlineContent = {
                    Text(
                        if (splitTunnelMode == SettingsStore.SplitTunnelMode.INCLUDE)
                            stringResource(R.string.settings_included_apps)
                        else stringResource(R.string.settings_excluded_apps)
                    )
                },
                supportingContent = {
                    Text(
                        (if (splitTunnelMode == SettingsStore.SplitTunnelMode.INCLUDE) includedApps
                            else excludedApps)
                            .let {
                                if (it.isEmpty()) stringResource(R.string.settings_none)
                                else pluralStringResource(R.plurals.settings_apps_count, it.size, it.size)
                            }
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.SPLIT_TUNNEL },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_section_advanced))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_custom_edge_address)) },
                supportingContent = { Text(customEdgeAddress.ifBlank { stringResource(R.string.not_set) }) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.EDGE_ADDRESS },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_transport_protocol)) },
                supportingContent = { Text(httpVersion.label) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.HTTP_VERSION },
            )
            SettingsToggle(
                title = stringResource(R.string.upstream_proxy),
                summary = stringResource(R.string.upstream_proxy_help),
                checked = proxyState.summary?.upstreamEnabled ?: settingsStore.upstreamProxyEnabled,
                enabled =
                    !proxyState.busy &&
                        (proxyState.summary != null || settingsStore.upstreamProxyEnabled),
                onCheckedChange = proxyViewModel::setUpstreamEnabled,
            )
            if (proxyState.summary?.upstreamEnabled == true) {
                if (proxyState.summary?.upstreamReviewRequired == true) {
                    Text(
                        stringResource(R.string.upstream_review_required),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.proxy_type)) },
                    supportingContent = { Text(proxyState.summary?.upstreamType?.name.orEmpty()) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    modifier =
                        Modifier.clickable(enabled = !proxyState.busy) {
                            proxyViewModel.open(ProxyEditor.UPSTREAM_TYPE)
                        },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.proxy_address)) },
                    supportingContent = {
                        val summary = proxyState.summary
                        val host = summary?.upstreamHost.orEmpty()
                        Text(
                            if (host.isBlank()) stringResource(R.string.not_set)
                            else "${if (':' in host) "[$host]" else host}:${summary?.upstreamPort}"
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    modifier =
                        Modifier.clickable(enabled = !proxyState.busy) {
                            proxyViewModel.open(ProxyEditor.UPSTREAM_ADDRESS)
                        },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.proxy_credentials)) },
                    supportingContent = { Text(stringResource(R.string.proxy_credentials_help)) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    modifier =
                        Modifier.clickable(enabled = !proxyState.busy) {
                            proxyViewModel.open(ProxyEditor.UPSTREAM_CREDENTIALS)
                        },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_section_anticensorship))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_tls_fragmentation)) },
                supportingContent = {
                    Text(
                        if (tlsFragmentMode == SettingsStore.TlsFragmentMode.OFF) {
                            stringResource(R.string.settings_disabled)
                        } else if (tlsFragmentMode == SettingsStore.TlsFragmentMode.RECORD) {
                            tlsFragmentMode.label
                        } else {
                            "${tlsFragmentMode.label}, ${fragmentFallbackDelay}ms"
                        }
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.TLS_FRAGMENT },
            )
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_browser_tls_fingerprint))
                },
                supportingContent = { Text(utlsFingerprint.label) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.UTLS_FINGERPRINT },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_multiplex_concurrency)) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.stream_count, maxConcurrentStreams, maxConcurrentStreams))
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { activeDialog = SettingsDialog.CONCURRENCY },
            )
            SettingsToggle(
                title = stringResource(R.string.settings_tcp_fast_open),
                summary = stringResource(R.string.settings_tcp_fast_open_help),
                checked = tcpFastOpen,
                onCheckedChange = {
                    settingsStore.tcpFastOpen = it
                    tcpFastOpen = it
                },
            )
            SettingsToggle(
                title = stringResource(R.string.settings_ipv6_tunnel),
                summary =
                    if (ipv6Enabled) stringResource(R.string.settings_dual_stack)
                    else stringResource(R.string.settings_ipv4_only),
                checked = ipv6Enabled,
                onCheckedChange = {
                    if (it && mtu < 1280) {
                        settingsStore.mtu = 1280
                        mtu = 1280
                    }
                    settingsStore.ipv6Enabled = it
                    ipv6Enabled = it
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_section_health))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_tunnel_health)) },
                supportingContent = {
                    Text(
                        when (health) {
                            TunnelHealth.UNKNOWN -> stringResource(R.string.settings_health_not_connected)
                            TunnelHealth.CHECKING -> stringResource(R.string.settings_health_checking)
                            TunnelHealth.HEALTHY -> stringResource(R.string.settings_health_healthy)
                            TunnelHealth.NO_NETWORK -> stringResource(R.string.settings_health_no_network)
                            TunnelHealth.UNREACHABLE ->
                                stringResource(R.string.settings_health_unreachable)
                        }
                    )
                },
            )
            SettingsToggle(
                title = stringResource(R.string.settings_automatic_connection_recovery),
                summary = stringResource(R.string.settings_recovery_help),
                checked = automaticRecovery,
                onCheckedChange = {
                    settingsStore.automaticRecovery = it
                    automaticRecovery = it
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.settings_section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_view_logs)) },
                supportingContent = { Text(stringResource(R.string.settings_logs_help)) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onOpenLogs),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_check_for_updates)) },
                supportingContent = { Text(stringResource(R.string.settings_update_source)) },
                modifier =
                    Modifier.clickable(enabled = onCheckForUpdates != null) {
                        onCheckForUpdates?.invoke()
                    },
            )
            SettingsToggle(
                title = stringResource(R.string.settings_automatic_update_checks),
                summary = stringResource(R.string.settings_automatic_updates_help),
                checked = automaticUpdates,
                enabled = onAutomaticUpdatesChange != null,
                onCheckedChange = {
                    onAutomaticUpdatesChange?.invoke(it)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_source_code)) },
                supportingContent = { Text(GITHUB_URL) },
                leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                                )
                            }
                            .onFailure {
                                android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.browser_unavailable),
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                    },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Button(
                onClick = onSignOut,
                enabled = !signOutInProgress,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_sign_out))
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier =
            Modifier.toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .heightIn(min = 48.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
