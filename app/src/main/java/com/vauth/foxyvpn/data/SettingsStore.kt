package com.vauth.foxyvpn.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vauth.foxyvpn.ui.theme.ThemeMode

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("foxyvpn_settings", Context.MODE_PRIVATE)

    private val credentialsPrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "foxyvpn_settings_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_THEME_MODE, null)
        } ?: ThemeMode.SYSTEM
        set(value) { prefs.edit().putString(KEY_THEME_MODE, value.name).apply() }

    var exitCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXIT_CHECK, true)
        set(value) { prefs.edit().putBoolean(KEY_EXIT_CHECK, value).apply() }

    var socksBindAddress: String
        get() = prefs.getString(KEY_SOCKS_BIND_ADDRESS, DEFAULT_SOCKS_BIND_ADDRESS) ?: DEFAULT_SOCKS_BIND_ADDRESS
        set(value) {
            prefs.edit().putString(KEY_SOCKS_BIND_ADDRESS, value.ifBlank { DEFAULT_SOCKS_BIND_ADDRESS }).apply()
        }

    var socksPort: Int
        get() = prefs.getInt(KEY_SOCKS_PORT, DEFAULT_SOCKS_PORT)
        set(value) { prefs.edit().putInt(KEY_SOCKS_PORT, value.coerceIn(1, 65_535)).apply() }

    var dohProvider: DohProvider
        get() = DohProvider.entries.firstOrNull {
            it.name == prefs.getString(KEY_DOH_PROVIDER, null)
        } ?: DohProvider.AUTOMATIC
        set(value) { prefs.edit().putString(KEY_DOH_PROVIDER, value.name).apply() }

    var customDnsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_DNS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CUSTOM_DNS_ENABLED, value).apply() }

    var customDnsServer: String
        get() = (prefs.getString(KEY_CUSTOM_DNS_SERVER, null) ?: DEFAULT_CUSTOM_DNS_SERVER)
            .let { if (isValidDnsServer(it)) it else DEFAULT_CUSTOM_DNS_SERVER }
        set(value) {
            val trimmed = value.trim()
            if (!isValidDnsServer(trimmed)) return
            prefs.edit().putString(KEY_CUSTOM_DNS_SERVER, trimmed).apply()
        }

    val effectiveCustomDnsServer: String?
        get() = if (customDnsEnabled) customDnsServer.takeIf { isValidDnsServer(it) } else null

    var proxyOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ONLY_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_PROXY_ONLY_MODE, value).apply() }

    var customEdgeAddress: String
        get() = prefs.getString(KEY_CUSTOM_EDGE_ADDRESS, "") ?: ""
        set(value) {
            val normalized = value.trim().lowercase()
            if (normalized.isNotEmpty() && !isValidEdgeHost(normalized)) return
            prefs.edit().putString(KEY_CUSTOM_EDGE_ADDRESS, normalized).apply()
        }

    val effectiveCustomEdgeAddress: String?
        get() = customEdgeAddress.takeIf { isValidEdgeHost(it) }

    var upstreamProxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPSTREAM_PROXY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_UPSTREAM_PROXY_ENABLED, value).apply() }

    var upstreamProxyType: UpstreamProxyType
        get() = UpstreamProxyType.entries.firstOrNull {
            it.name == prefs.getString(KEY_UPSTREAM_PROXY_TYPE, null)
        } ?: UpstreamProxyType.SOCKS5
        set(value) { prefs.edit().putString(KEY_UPSTREAM_PROXY_TYPE, value.name).apply() }

    var upstreamProxyHost: String
        get() = prefs.getString(KEY_UPSTREAM_PROXY_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_UPSTREAM_PROXY_HOST, value.trim()).apply() }

    var upstreamProxyPort: Int
        get() = prefs.getInt(KEY_UPSTREAM_PROXY_PORT, DEFAULT_UPSTREAM_PROXY_PORT)
        set(value) { prefs.edit().putInt(KEY_UPSTREAM_PROXY_PORT, value.coerceIn(1, 65_535)).apply() }

    var upstreamProxyUsername: String
        get() = credentialsPrefs.getString(KEY_UPSTREAM_PROXY_USERNAME, "") ?: ""
        set(value) { credentialsPrefs.edit().putString(KEY_UPSTREAM_PROXY_USERNAME, value).apply() }

    var upstreamProxyPassword: String
        get() = credentialsPrefs.getString(KEY_UPSTREAM_PROXY_PASSWORD, "") ?: ""
        set(value) { credentialsPrefs.edit().putString(KEY_UPSTREAM_PROXY_PASSWORD, value).apply() }

    var excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {

            prefs.edit().putStringSet(KEY_EXCLUDED_APPS, HashSet(value)).apply()
        }

    enum class UpstreamProxyType { SOCKS5, HTTP }

    enum class DohProvider(val label: String) {
        AUTOMATIC("Automatic"),
        CLOUDFLARE("Cloudflare"),
        GOOGLE("Google"),
        QUAD9("Quad9"),
        OFF("Off (use the network's resolver)"),
        ;

        val addresses: List<String>
            get() = when (this) {
                AUTOMATIC -> listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
                CLOUDFLARE -> listOf("1.1.1.1", "1.0.0.1")
                GOOGLE -> listOf("8.8.8.8", "8.8.4.4")
                QUAD9 -> listOf("9.9.9.9", "149.112.112.112")
                OFF -> emptyList()
            }
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_EXIT_CHECK = "exit_check_enabled"
        private const val KEY_SOCKS_BIND_ADDRESS = "socks_bind_address"
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_DOH_PROVIDER = "doh_provider"
        private const val KEY_CUSTOM_DNS_ENABLED = "custom_dns_enabled"
        private const val KEY_CUSTOM_DNS_SERVER = "custom_dns_server"
        private const val KEY_PROXY_ONLY_MODE = "proxy_only_mode"
        private const val KEY_CUSTOM_EDGE_ADDRESS = "custom_edge_address"
        private const val KEY_UPSTREAM_PROXY_ENABLED = "upstream_proxy_enabled"
        private const val KEY_UPSTREAM_PROXY_TYPE = "upstream_proxy_type"
        private const val KEY_UPSTREAM_PROXY_HOST = "upstream_proxy_host"
        private const val KEY_UPSTREAM_PROXY_PORT = "upstream_proxy_port"
        private const val KEY_UPSTREAM_PROXY_USERNAME = "upstream_proxy_username"
        private const val KEY_UPSTREAM_PROXY_PASSWORD = "upstream_proxy_password"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        const val DEFAULT_SOCKS_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_UPSTREAM_PROXY_PORT = 1080
        const val DEFAULT_CUSTOM_DNS_SERVER = "1.1.1.1"

        val SOCKS_BIND_ADDRESS_PRESETS = listOf(
            "127.0.0.1" to "Loopback only (127.0.0.1)",
            "0.0.0.0" to "All interfaces (0.0.0.0)",
        )

        val CUSTOM_DNS_PRESETS = listOf(
            "1.1.1.1" to "Cloudflare (1.1.1.1)",
            "8.8.8.8" to "Google (8.8.8.8)",
            "9.9.9.9" to "Quad9 (9.9.9.9)",
        )

        fun isValidDnsServer(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false

            if (parts.any { it.length > 1 && it.startsWith("0") }) return false
            val octets = parts.map { part -> part.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] != 0 && octets[0] != 127 && octets[0] < 224
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

        fun isValidEdgeHost(value: String): Boolean {
            val host = value.trim().removeSuffix(".")
            if (host.isEmpty() || host.length > 253) return false

            if (host.contains(':')) {
                if (host.count { it == ':' } > 8) return false
                val compressed = host.contains("::")
                val groups = host.split(':').filter { it.isNotEmpty() }
                if (groups.isEmpty() || groups.size > 8) return false
                if (!compressed && groups.size != 8) return false
                return groups.all { group ->
                    group.length <= 4 && group.all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' }
                }
            }

            if (host.all { it.isDigit() || it == '.' }) {
                val parts = host.split('.')
                if (parts.size != 4) return false
                if (parts.any { it.length > 1 && it.startsWith("0") }) return false
                val octets = parts.map { part -> part.toIntOrNull() ?: return false }
                return octets.all { it in 0..255 } && octets[0] != 0 && octets[0] != 127
            }
            return isValidHostname(host)
        }
    }
}
