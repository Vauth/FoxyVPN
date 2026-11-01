package com.vauth.foxyvpn.data

data class TunnelSettings(
    val automaticRecovery: Boolean,
    val dnsProvider: SettingsStore.DnsProvider,
    val effectiveCustomEdgeAddress: String?,
    val effectiveDohUrl: String,
    val excludedApps: Set<String>,
    val includedApps: Set<String>,
    val splitTunnelMode: SettingsStore.SplitTunnelMode,
    val fragmentFallbackDelay: Int,
    val httpVersion: SettingsStore.HttpVersion,
    val ipv6Enabled: Boolean,
    val maxConcurrentStreams: Int,
    val mtu: Int,
    val localProxyAuthEnabled: Boolean,
    val localProxyUsername: String,
    val localProxyPassword: String,
    val proxyOnlyMode: Boolean,
    val socksBindAddress: String,
    val socksPort: Int,
    val tcpFastOpen: Boolean,
    val tlsFragmentMode: SettingsStore.TlsFragmentMode,
    val upstreamProxyEnabled: Boolean,
    val upstreamProxyHost: String,
    val upstreamProxyPassword: String,
    val upstreamProxyPort: Int,
    val upstreamProxyType: SettingsStore.UpstreamProxyType,
    val upstreamProxyUsername: String,
    val utlsFingerprint: SettingsStore.UtlsFingerprint,
    val bypassLocalNetwork: Boolean = true,
) {
    override fun toString() =
        "TunnelSettings(proxyOnlyMode=$proxyOnlyMode, httpVersion=$httpVersion)"
}

fun SettingsStore.tunnelSnapshot(): TunnelSettings =
    synchronized(proxySettingsLock) {
        require(!upstreamProxyEnabled || !upstreamReviewRequired) {
            "Upstream TLS has been removed. Review the upstream proxy settings before connecting."
        }
        TunnelSettings(
            automaticRecovery = automaticRecovery,
            bypassLocalNetwork = bypassLocalNetwork,
            dnsProvider = dnsProvider,
            effectiveCustomEdgeAddress = effectiveCustomEdgeAddress,
            effectiveDohUrl = effectiveDohUrl,
            excludedApps = excludedApps.toSet(),
            includedApps = includedApps.toSet(),
            splitTunnelMode = splitTunnelMode,
            fragmentFallbackDelay = fragmentFallbackDelay,
            httpVersion = httpVersion,
            ipv6Enabled = ipv6Enabled,
            maxConcurrentStreams = maxConcurrentStreams,
            mtu = mtu,
            localProxyAuthEnabled = localProxyAuthEnabled,
            localProxyUsername = if (localProxyAuthEnabled) localProxyUsername else "",
            localProxyPassword = if (localProxyAuthEnabled) localProxyPassword else "",
            proxyOnlyMode = proxyOnlyMode,
            socksBindAddress = socksBindAddress,
            socksPort = socksPort,
            tcpFastOpen = tcpFastOpen,
            tlsFragmentMode = tlsFragmentMode,
            upstreamProxyEnabled = upstreamProxyEnabled,
            upstreamProxyHost = if (upstreamProxyEnabled) upstreamProxyHost else "",
            upstreamProxyPassword = if (upstreamProxyEnabled) upstreamProxyPassword else "",
            upstreamProxyPort =
                if (upstreamProxyEnabled) upstreamProxyPort
                else SettingsStore.DEFAULT_UPSTREAM_PROXY_PORT,
            upstreamProxyType =
                if (upstreamProxyEnabled) upstreamProxyType
                else SettingsStore.UpstreamProxyType.SOCKS5,
            upstreamProxyUsername = if (upstreamProxyEnabled) upstreamProxyUsername else "",
            utlsFingerprint = utlsFingerprint,
        )
    }
