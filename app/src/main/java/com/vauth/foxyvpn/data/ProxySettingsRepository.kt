package com.vauth.foxyvpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalProxyOptions(
    val bindAddress: String = SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS,
    val port: Int,
    val authentication: Boolean,
    val username: String,
    val password: String,
) {
    override fun toString() =
        "LocalProxyOptions(bindAddress=$bindAddress, port=$port, authentication=$authentication)"
}

class UpstreamProxyOptions(
    val enabled: Boolean,
    val type: SettingsStore.UpstreamProxyType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    override fun toString() = "UpstreamProxyOptions(enabled=$enabled, type=$type)"
}

data class ProxySummary(
    val localBindAddress: String,
    val localPort: Int,
    val upstreamEnabled: Boolean,
    val upstreamType: SettingsStore.UpstreamProxyType,
    val upstreamHost: String,
    val upstreamPort: Int,
    val upstreamReviewRequired: Boolean,
)

class ProxySettingsRepository(private val store: SettingsStore) {
    suspend fun summary(): ProxySummary =
        withContext(Dispatchers.IO) {
            synchronized(store.proxySettingsLock) {
                ProxySummary(
                    store.socksBindAddress,
                    store.socksPort,
                    store.upstreamProxyEnabled,
                    store.upstreamProxyType,
                    store.upstreamProxyHost,
                    store.upstreamProxyPort,
                    store.upstreamReviewRequired,
                )
            }
        }

    suspend fun local(): LocalProxyOptions =
        withContext(Dispatchers.IO) {
            synchronized(store.proxySettingsLock) {
                LocalProxyOptions(
                    store.socksBindAddress,
                    store.socksPort,
                    store.localProxyAuthEnabled,
                    store.localProxyUsername,
                    store.localProxyPassword,
                )
            }
        }

    suspend fun upstream(): UpstreamProxyOptions =
        withContext(Dispatchers.IO) {
            synchronized(store.proxySettingsLock) {
                UpstreamProxyOptions(
                    store.upstreamProxyEnabled,
                    store.upstreamProxyType,
                    store.upstreamProxyHost,
                    store.upstreamProxyPort,
                    store.upstreamProxyUsername,
                    store.upstreamProxyPassword,
                )
            }
        }

    suspend fun setUpstreamEnabled(enabled: Boolean) =
        withContext(Dispatchers.IO) { store.setUpstreamProxyEnabled(enabled) }

    suspend fun save(options: LocalProxyOptions) =
        withContext(Dispatchers.IO) { store.saveLocalProxy(options) }

    suspend fun save(options: UpstreamProxyOptions) =
        withContext(Dispatchers.IO) { store.saveUpstreamProxy(options) }
}
