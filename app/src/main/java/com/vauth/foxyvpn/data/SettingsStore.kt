package com.vauth.foxyvpn.data

import android.content.Context

class SettingsStore(context: Context) {
    internal val proxySettingsLock = Any()
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("foxyvpn_settings", Context.MODE_PRIVATE)

    private fun <T> readCredential(block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            throw error as? SecureStorageException ?: SecureStorageException(error)
        }

    private val credentialsPrefs by lazy {
        SecurePreferences(appContext, "foxyvpn_settings_credentials")
    }

    data class AccountSnapshot(
        val realIp: String?,
        val country: String?,
        val quotaRemaining: Long?,
        val quotaMax: Long?,
        val unlimited: Boolean,
    )

    private fun accountSnapshot() =
        AccountSnapshot(
            cachedRealIp,
            cachedRealCountry,
            cachedQuotaRemaining,
            cachedQuotaMax,
            cachedIsUnlimited,
        )

    private val mutableAccount = kotlinx.coroutines.flow.MutableStateFlow(accountSnapshot())
    val account: kotlinx.coroutines.flow.StateFlow<AccountSnapshot> = mutableAccount

    private val accountListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == null ||
                    key in
                        setOf(
                            KEY_CACHED_REAL_IP,
                            KEY_CACHED_REAL_COUNTRY,
                            KEY_CACHED_QUOTA_REMAINING,
                            KEY_CACHED_QUOTA_MAX,
                            KEY_CACHED_IS_UNLIMITED,
                        )
            ) {
                mutableAccount.value = accountSnapshot()
            }
        }

    init {

        if (prefs.getString(KEY_DNS_PROVIDER, null) == "ADGUARD") {
            prefs.edit().putString(KEY_DNS_PROVIDER, DnsProvider.GOOGLE.name).apply()
        }
        prefs.registerOnSharedPreferenceChangeListener(accountListener)
    }

    var bypassLocalNetwork: Boolean
        get() = prefs.getBoolean("bypass_local_network", true)
        set(value) {
            prefs.edit().putBoolean("bypass_local_network", value).apply()
        }

    var automaticRecovery: Boolean
        get() = prefs.getBoolean("automatic_recovery", false)
        set(value) {
            prefs.edit().putBoolean("automatic_recovery", value).apply()
        }

    var themeMode: ThemeMode
        get() =
            ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME_MODE, null) }
                ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    var latencyProbeUrl: String
        get() =
            prefs.getString(KEY_LATENCY_PROBE_URL, null)?.takeIf { isValidProbeUrl(it) }
                ?: DEFAULT_LATENCY_PROBE_URL
        set(value) {
            val url = value.trim()
            require(isValidProbeUrl(url)) {
                "Latency endpoint must be HTTPS without credentials or fragment"
            }
            prefs.edit().putString(KEY_LATENCY_PROBE_URL, url).apply()
        }

    var mtu: Int
        get() = prefs.getInt(KEY_CUSTOM_MTU, DEFAULT_MTU)
        set(value) {
            prefs.edit().putInt(KEY_CUSTOM_MTU, value.coerceIn(MIN_MTU, MAX_MTU)).apply()
        }

    var cachedRealIp: String?
        get() = prefs.getString(KEY_CACHED_REAL_IP, null)
        set(value) {
            prefs.edit().putString(KEY_CACHED_REAL_IP, value).apply()
        }

    var cachedRealCountry: String?
        get() = prefs.getString(KEY_CACHED_REAL_COUNTRY, null)
        set(value) {
            prefs.edit().putString(KEY_CACHED_REAL_COUNTRY, value).apply()
        }

    var cachedQuotaRemaining: Long?
        get() =
            if (prefs.contains(KEY_CACHED_QUOTA_REMAINING))
                prefs.getLong(KEY_CACHED_QUOTA_REMAINING, -1L).takeIf { it >= 0 }
            else null
        set(value) {
            if (value != null && value >= 0)
                prefs.edit().putLong(KEY_CACHED_QUOTA_REMAINING, value).apply()
            else prefs.edit().remove(KEY_CACHED_QUOTA_REMAINING).apply()
        }

    var cachedQuotaMax: Long?
        get() =
            if (prefs.contains(KEY_CACHED_QUOTA_MAX))
                prefs.getLong(KEY_CACHED_QUOTA_MAX, -1L).takeIf { it >= 0 }
            else null
        set(value) {
            if (value != null && value >= 0)
                prefs.edit().putLong(KEY_CACHED_QUOTA_MAX, value).apply()
            else prefs.edit().remove(KEY_CACHED_QUOTA_MAX).apply()
        }

    var cachedIsUnlimited: Boolean
        get() = prefs.getBoolean(KEY_CACHED_IS_UNLIMITED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CACHED_IS_UNLIMITED, value).apply()
        }

    fun saveDirectAddress(ip: String, country: String) {
        prefs
            .edit()
            .putString(KEY_CACHED_REAL_IP, ip)
            .putString(KEY_CACHED_REAL_COUNTRY, country)
            .apply()
        mutableAccount.value = accountSnapshot()
    }

    fun saveAccountQuota(unlimited: Boolean, remaining: Long?, maximum: Long?) {
        val editor = prefs.edit().putBoolean(KEY_CACHED_IS_UNLIMITED, unlimited)
        if (remaining != null && remaining >= 0) {
            editor.putLong(KEY_CACHED_QUOTA_REMAINING, remaining)
        } else if (unlimited) {
            editor.remove(KEY_CACHED_QUOTA_REMAINING)
        }
        if (maximum != null && maximum >= 0) {
            editor.putLong(KEY_CACHED_QUOTA_MAX, maximum)
        } else if (unlimited) {
            editor.remove(KEY_CACHED_QUOTA_MAX)
        }
        editor.apply()
        mutableAccount.value = accountSnapshot()
    }

    fun clearAccountSession() {
        prefs
            .edit()
            .remove(KEY_CACHED_REAL_IP)
            .remove(KEY_CACHED_REAL_COUNTRY)
            .remove(KEY_CACHED_QUOTA_REMAINING)
            .remove(KEY_CACHED_QUOTA_MAX)
            .remove(KEY_CACHED_IS_UNLIMITED)
            .apply()
        mutableAccount.value = accountSnapshot()
    }

    val socksBindAddress: String
        get() = DEFAULT_SOCKS_BIND_ADDRESS

    val socksPort: Int
        get() =
            if (localProxyAuthEnabled)
                readCredential {
                    credentialsPrefs
                        .getLong(
                            KEY_SOCKS_PORT,
                            prefs.getInt(KEY_SOCKS_PORT, DEFAULT_SOCKS_PORT).toLong(),
                        )
                        .toInt()
                }
            else prefs.getInt(KEY_SOCKS_PORT, DEFAULT_SOCKS_PORT)

    var dnsProvider: DnsProvider
        get() {
            val saved = prefs.getString(KEY_DNS_PROVIDER, null)
            if (saved == "SYSTEM_DEFAULT" || saved == null) return DnsProvider.GOOGLE
            return DnsProvider.entries.firstOrNull { it.name == saved } ?: DnsProvider.GOOGLE
        }
        set(value) {
            prefs.edit().putString(KEY_DNS_PROVIDER, value.name).apply()
        }

    var customDohUrl: String
        get() = prefs.getString(KEY_CUSTOM_DOH_URL, "") ?: ""
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty() && !isValidDohUrl(trimmed)) return
            prefs.edit().putString(KEY_CUSTOM_DOH_URL, trimmed).apply()
        }

    fun saveDns(provider: DnsProvider, customUrl: String) {
        val url = customUrl.trim()
        require(provider != DnsProvider.CUSTOM || isValidDohUrl(url)) {
            "Enter a valid HTTPS DoH URL"
        }
        val savedUrl =
            url.takeIf { it.isEmpty() || isValidDohUrl(it) }
                ?: customDohUrl.takeIf { isValidDohUrl(it) }.orEmpty()
        prefs
            .edit()
            .putString(KEY_DNS_PROVIDER, provider.name)
            .putString(KEY_CUSTOM_DOH_URL, savedUrl)
            .apply()
    }

    val effectiveDohUrl: String
        get() =
            when (dnsProvider) {
                DnsProvider.CUSTOM -> customDohUrl
                DnsProvider.DIRECT_SYSTEM -> ""
                else -> dnsProvider.dohUrl
            }

    var tlsFragmentMode: TlsFragmentMode
        get() =
            TlsFragmentMode.entries.firstOrNull {
                it.name == prefs.getString(KEY_TLS_FRAGMENT_MODE, null)
            } ?: TlsFragmentMode.OFF
        set(value) {
            prefs.edit().putString(KEY_TLS_FRAGMENT_MODE, value.name).apply()
        }

    var fragmentFallbackDelay: Int
        get() = prefs.getInt(KEY_FRAGMENT_FALLBACK_DELAY, DEFAULT_FRAGMENT_FALLBACK_DELAY)
        set(value) {
            prefs.edit().putInt(KEY_FRAGMENT_FALLBACK_DELAY, value.coerceIn(50, 2000)).apply()
        }

    var utlsFingerprint: UtlsFingerprint
        get() =
            UtlsFingerprint.entries.firstOrNull {
                it.name == prefs.getString(KEY_UTLS_FINGERPRINT, null)
            } ?: UtlsFingerprint.FIREFOX
        set(value) {
            prefs.edit().putString(KEY_UTLS_FINGERPRINT, value.name).apply()
        }

    var tcpFastOpen: Boolean
        get() = prefs.getBoolean(KEY_TCP_FAST_OPEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TCP_FAST_OPEN, value).apply()
        }

    var maxConcurrentStreams: Int
        get() = prefs.getInt(KEY_MAX_CONCURRENT_STREAMS, DEFAULT_MAX_CONCURRENT_STREAMS)
        set(value) {
            prefs.edit().putInt(KEY_MAX_CONCURRENT_STREAMS, value.coerceIn(2, 128)).apply()
        }

    var ipv6Enabled: Boolean
        get() = prefs.getBoolean(KEY_IPV6_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IPV6_ENABLED, value).apply()
        }

    var proxyOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ONLY_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PROXY_ONLY_MODE, value).apply()
        }

    var customEdgeAddress: String
        get() = prefs.getString(KEY_CUSTOM_EDGE_ADDRESS, "") ?: ""
        set(value) {
            val normalized = value.trim().lowercase()
            if (normalized.isNotEmpty() && !isValidEdgeHost(normalized)) return
            prefs.edit().putString(KEY_CUSTOM_EDGE_ADDRESS, normalized).apply()
        }

    val effectiveCustomEdgeAddress: String?
        get() = customEdgeAddress.takeIf { isValidEdgeHost(it) }

    val localProxyAuthEnabled: Boolean
        get() = prefs.getBoolean("local_proxy_auth_enabled", false)

    val localProxyUsername: String
        get() = readCredential { credentialsPrefs.getString("local_proxy_username", "") ?: "" }

    val localProxyPassword: String
        get() = readCredential { credentialsPrefs.getString("local_proxy_password", "") ?: "" }

    val upstreamProxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPSTREAM_PROXY_ENABLED, false)

    val upstreamProxyType: UpstreamProxyType
        get() =
            UpstreamProxyType.entries.firstOrNull {
                it.name ==
                    readCredential {
                        credentialsPrefs.getString(
                            KEY_UPSTREAM_PROXY_TYPE,
                            prefs.getString(KEY_UPSTREAM_PROXY_TYPE, null),
                        )
                    }
            } ?: UpstreamProxyType.SOCKS5

    val upstreamProxyHost: String
        get() = readCredential {
            credentialsPrefs
                .getString(KEY_UPSTREAM_PROXY_HOST, prefs.getString(KEY_UPSTREAM_PROXY_HOST, ""))
                .orEmpty()
                .trim()
                .removeSurrounding("[", "]")
        }

    fun saveLocalProxy(options: LocalProxyOptions) =
        synchronized(proxySettingsLock) {
            require(isValidLocalPort(options.port)) {
                "Choose a valid listener port, excluding controller port $CONTROLLER_PORT"
            }
            require(
                !options.authentication ||
                    isValidLocalProxyCredentials(options.username, options.password)
            ) {
                "Enter valid credentials for both HTTP and SOCKS5"
            }
            require(
                !upstreamProxyEnabled ||
                    !isLocalProxyLoop(upstreamProxyHost, upstreamProxyPort, options.port)
            ) {
                "Upstream proxy must not point to this app's listeners"
            }
            readCredential {
                credentialsPrefs
                    .edit()
                    .putString("local_proxy_username", options.username)
                    .putString("local_proxy_password", options.password)
                    .putLong(KEY_SOCKS_PORT, options.port.toLong())
                    .commit()
            }
            check(
                prefs
                    .edit()
                    .putInt(KEY_SOCKS_PORT, options.port)
                    .putBoolean("local_proxy_auth_enabled", options.authentication)
                    .commit()
            ) {
                "Could not persist local proxy settings"
            }
        }

    val upstreamReviewRequired: Boolean
        get() = readCredential {
            credentialsPrefs.getLong(
                "upstream_review_required",
                if (prefs.getBoolean("upstream_proxy_tls", false)) 1 else 0,
            ) == 1L
        }

    fun setUpstreamProxyEnabled(enabled: Boolean) =
        synchronized(proxySettingsLock) {
            check(prefs.edit().putBoolean(KEY_UPSTREAM_PROXY_ENABLED, enabled).commit()) {
                "Could not persist the upstream proxy switch"
            }
        }

    fun disableUpstreamProxy() = setUpstreamProxyEnabled(false)

    fun saveUpstreamProxy(options: UpstreamProxyOptions) =
        synchronized(proxySettingsLock) {
            val host = options.host.trim().removeSurrounding("[", "]")
            require((host.isEmpty() || isValidProxyHost(host)) && options.port in 1..65_535) {
                "Enter a valid proxy host and port"
            }
            require(
                !options.enabled ||
                    isValidUpstreamProxyCredentials(
                        options.type,
                        options.username,
                        options.password,
                    )
            ) {
                "Credentials are not valid for the selected proxy type"
            }
            require(!options.enabled || !isLocalProxyLoop(host, options.port, socksPort)) {
                "Upstream proxy must not point to this app's listeners"
            }
            readCredential {
                credentialsPrefs
                    .edit()
                    .putString(KEY_UPSTREAM_PROXY_USERNAME, options.username)
                    .putString(KEY_UPSTREAM_PROXY_PASSWORD, options.password)
                    .putString(KEY_UPSTREAM_PROXY_TYPE, options.type.name)
                    .putString(KEY_UPSTREAM_PROXY_HOST, host)
                    .putLong(KEY_UPSTREAM_PROXY_PORT, options.port.toLong())
                    .also { if (options.enabled) it.putLong("upstream_review_required", 0) }
                    .commit()
            }
            setUpstreamProxyEnabled(options.enabled)
        }

    val upstreamProxyPort: Int
        get() = readCredential {
            credentialsPrefs
                .getLong(
                    KEY_UPSTREAM_PROXY_PORT,
                    prefs.getInt(KEY_UPSTREAM_PROXY_PORT, DEFAULT_UPSTREAM_PROXY_PORT).toLong(),
                )
                .toInt()
        }

    val upstreamProxyUsername: String
        get() = readCredential { credentialsPrefs.getString(KEY_UPSTREAM_PROXY_USERNAME, "") ?: "" }

    val upstreamProxyPassword: String
        get() = readCredential { credentialsPrefs.getString(KEY_UPSTREAM_PROXY_PASSWORD, "") ?: "" }

    var excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_EXCLUDED_APPS, HashSet(value)).apply()
        }

    enum class SplitTunnelMode {
        EXCLUDE,
        INCLUDE,
    }

    var splitTunnelMode: SplitTunnelMode
        get() =
            SplitTunnelMode.entries.firstOrNull {
                it.name == prefs.getString(KEY_SPLIT_TUNNEL_MODE, null)
            } ?: SplitTunnelMode.EXCLUDE
        set(value) {
            prefs.edit().putString(KEY_SPLIT_TUNNEL_MODE, value.name).apply()
        }

    var includedApps: Set<String>
        get() = prefs.getStringSet(KEY_INCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_INCLUDED_APPS, HashSet(value)).apply()
        }

    fun saveSplitTunneling(mode: SplitTunnelMode, excluded: Set<String>, included: Set<String>) {
        require(mode != SplitTunnelMode.INCLUDE || included.isNotEmpty()) {
            "Select at least one app to include"
        }
        prefs
            .edit()
            .putString(KEY_SPLIT_TUNNEL_MODE, mode.name)
            .putStringSet(KEY_EXCLUDED_APPS, HashSet(excluded))
            .putStringSet(KEY_INCLUDED_APPS, HashSet(included))
            .apply()
    }

    enum class HttpVersion(val versionNumber: Int, val label: String, val description: String) {
        HTTP_2(versionNumber = 2, label = "HTTP/2", description = "Multiplexed streams over TCP"),
        HTTP_3(versionNumber = 3, label = "HTTP/3", description = "QUIC transport over UDP"),
    }

    var httpVersion: HttpVersion
        get() =
            HttpVersion.entries.firstOrNull { it.name == prefs.getString(KEY_HTTP_VERSION, null) }
                ?: HttpVersion.HTTP_2
        set(value) {
            prefs.edit().putString(KEY_HTTP_VERSION, value.name).apply()
        }

    enum class UpstreamProxyType {
        SOCKS5,
        HTTP,
    }

    enum class TlsFragmentMode(val label: String, val description: String) {
        OFF(label = "Disabled", description = "Standard TLS"),
        RECORD(label = "Record", description = "TLS record layer"),
        PACKET(label = "Packet", description = "TCP segment layer"),
        COMBINED(label = "Combined", description = "Record and packet"),
    }

    enum class UtlsFingerprint(val label: String, val identifier: String) {
        FIREFOX("Firefox", "firefox"),
        CHROME("Chrome", "chrome"),
        SAFARI("Safari", "safari"),
        IOS("iOS", "ios"),
        EDGE("Edge", "edge"),
        RANDOMIZED("Randomized", "randomized"),
    }

    enum class DnsProvider(val label: String, val dohUrl: String) {
        DIRECT_SYSTEM(label = "System DNS", dohUrl = ""),
        GOOGLE(label = "Google", dohUrl = "https://8.8.8.8/dns-query"),
        CLOUDFLARE(label = "Cloudflare", dohUrl = "https://1.1.1.1/dns-query"),
        QUAD9(label = "Quad9", dohUrl = "https://9.9.9.9/dns-query"),
        CUSTOM(label = "Custom DoH", dohUrl = "");

        val isDirect: Boolean
            get() = this == DIRECT_SYSTEM
    }

    companion object {
        const val DEFAULT_LATENCY_PROBE_URL = "https://www.gstatic.com/generate_204"
        private const val KEY_LATENCY_PROBE_URL = "latency_probe_url"

        fun isValidProbeUrl(value: String): Boolean =
            runCatching {
                    val uri = java.net.URI(value)
                    uri.scheme.equals("https", ignoreCase = true) &&
                        !uri.host.isNullOrBlank() &&
                        uri.rawUserInfo == null &&
                        uri.rawFragment == null &&
                        (uri.port == -1 || uri.port in 1..65_535)
                }
                .getOrDefault(false)

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_CUSTOM_MTU = "custom_mtu"
        private const val KEY_CACHED_REAL_IP = "cached_real_ip"
        private const val KEY_CACHED_REAL_COUNTRY = "cached_real_country"
        private const val KEY_CACHED_QUOTA_REMAINING = "cached_quota_remaining"
        private const val KEY_CACHED_QUOTA_MAX = "cached_quota_max"
        private const val KEY_CACHED_IS_UNLIMITED = "cached_is_unlimited"
        const val DEFAULT_MTU = 1280
        const val MIN_MTU = 576
        const val MAX_MTU = 9000
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_DNS_PROVIDER = "dns_provider"
        private const val KEY_CUSTOM_DOH_URL = "custom_doh_url"
        private const val KEY_TLS_FRAGMENT_MODE = "tls_fragment_mode"
        private const val KEY_FRAGMENT_FALLBACK_DELAY = "fragment_fallback_delay"
        private const val KEY_UTLS_FINGERPRINT = "utls_fingerprint"
        private const val KEY_TCP_FAST_OPEN = "tcp_fast_open"
        private const val KEY_MAX_CONCURRENT_STREAMS = "max_concurrent_streams"
        private const val KEY_IPV6_ENABLED = "ipv6_enabled"
        const val DEFAULT_FRAGMENT_FALLBACK_DELAY = 300
        const val DEFAULT_MAX_CONCURRENT_STREAMS = 32
        private const val KEY_PROXY_ONLY_MODE = "proxy_only_mode"
        private const val KEY_CUSTOM_EDGE_ADDRESS = "custom_edge_address"
        private const val KEY_UPSTREAM_PROXY_ENABLED = "upstream_proxy_enabled"
        private const val KEY_UPSTREAM_PROXY_TYPE = "upstream_proxy_type"
        private const val KEY_UPSTREAM_PROXY_HOST = "upstream_proxy_host"
        private const val KEY_UPSTREAM_PROXY_PORT = "upstream_proxy_port"
        private const val KEY_UPSTREAM_PROXY_USERNAME = "upstream_proxy_username"
        private const val KEY_UPSTREAM_PROXY_PASSWORD = "upstream_proxy_password"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        private const val KEY_INCLUDED_APPS = "included_apps"
        private const val KEY_SPLIT_TUNNEL_MODE = "split_tunnel_mode"
        private const val KEY_HTTP_VERSION = "http_version"
        const val CONTROLLER_PORT = 9090
        const val DEFAULT_SOCKS_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_UPSTREAM_PROXY_PORT = 1080

        fun isValidUpstreamProxyCredentials(
            type: UpstreamProxyType,
            username: String,
            password: String,
        ): Boolean {
            if (username.isEmpty() && password.isEmpty()) return true
            if (
                username.isBlank() ||
                    username.any { it.isISOControl() } ||
                    password.any { it.isISOControl() }
            )
                return false
            return when (type) {
                UpstreamProxyType.SOCKS5 ->
                    username.toByteArray(Charsets.UTF_8).size in 1..255 &&
                        password.toByteArray(Charsets.UTF_8).size in 1..255
                UpstreamProxyType.HTTP ->
                    ':' !in username &&
                        username.toByteArray(Charsets.UTF_8).size <= 255 &&
                        password.toByteArray(Charsets.UTF_8).size <= 255
            }
        }

        fun isLocalProxyLoop(host: String, port: Int, mixedPort: Int): Boolean {
            val normalized = host.trim().removeSurrounding("[", "]").removeSuffix(".").lowercase()
            val loopback =
                normalized == "localhost" ||
                    normalized == "::1" ||
                    normalized == "::ffff:127.0.0.1" ||
                    (isValidIpAddress(normalized) &&
                        runCatching { java.net.InetAddress.getByName(normalized).isLoopbackAddress }
                            .getOrDefault(false))
            return loopback && (port == mixedPort || port == CONTROLLER_PORT)
        }

        fun isValidLocalProxyCredentials(username: String, password: String): Boolean =
            username.isNotBlank() &&
                password.isNotBlank() &&
                ':' !in username &&
                username.none { it.isISOControl() } &&
                password.none { it.isISOControl() } &&
                username.toByteArray(Charsets.UTF_8).size in 1..255 &&
                password.toByteArray(Charsets.UTF_8).size in 1..255

        fun isValidDohUrl(value: String): Boolean {
            val trimmed = value.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return false
            return runCatching {
                    val uri = java.net.URI.create(trimmed)
                    !uri.host.isNullOrBlank() &&
                        uri.rawUserInfo == null &&
                        uri.rawFragment == null &&
                        uri.rawQuery == null &&
                        (uri.port == -1 || uri.port in 1..65_535)
                }
                .getOrDefault(false)
        }

        fun isValidIpAddress(value: String): Boolean {
            if (value.isEmpty()) return false
            if (':' !in value) {
                val octets = value.split('.')
                return octets.size == 4 &&
                    octets.all { octet ->
                        octet.isNotEmpty() &&
                            octet.length <= 3 &&
                            octet.all { it in '0'..'9' } &&
                            (octet.length == 1 || octet.first() != '0') &&
                            octet.toInt() in 0..255
                    }
            }
            if (value.any { it !in "0123456789abcdefABCDEF:." }) return false
            return runCatching {
                    java.net.URI("http://[$value]/").host != null &&
                        java.net.InetAddress.getByName(value) != null
                }
                .getOrDefault(false)
        }

        fun isValidHostname(value: String): Boolean {
            val host = value.trim().removeSuffix(".")
            if (host.isEmpty() || host.length > 253) return false

            if (host.contains(':')) return false
            if (host.all { it.isDigit() || it == '.' }) return false
            val labels = host.split('.')
            if (labels.size < 2) return false
            return labels.all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    !label.startsWith('-') &&
                    !label.endsWith('-') &&
                    label.all { char ->
                        char == '-' || (char.code < 128 && (char.isLetterOrDigit()))
                    }
            }
        }

        fun isValidProxyHost(value: String): Boolean {
            val host = value.trim().removeSurrounding("[", "]").removeSuffix(".")
            if (isValidIpAddress(host) || isValidHostname(host)) return true

            return host.length in 1..63 &&
                host.any { it.isLetter() } &&
                !host.startsWith('-') &&
                !host.endsWith('-') &&
                host.all { it == '-' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        }

        fun isValidLocalPort(port: Int): Boolean = port in 1..65_535 && port != CONTROLLER_PORT

        fun isValidEdgeHost(value: String): Boolean {
            val host = value.trim().removeSuffix(".")
            return isValidHostname(host) || isValidIpAddress(host)
        }
    }
}
