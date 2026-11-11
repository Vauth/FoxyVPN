package com.vauth.foxyvpn.data

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

data class RelayerConfig(
    val provider: String,
    val customUrl: String,
    val relayedEndpoints: Set<String>,
    val availableRelayers: Map<String, String>,
    val failoverCodes: Set<Int> = DEFAULT_FAILOVER_CODES,
)

private const val DIRECT_CONNECT_TIMEOUT_SECONDS = 5

class RelayerInterceptor(
    private val configProvider: () -> RelayerConfig?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val config = configProvider() ?: return chain.proceed(chain.request())
        if (config.provider == SettingsStore.RELAYER_DIRECT) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        if (request.body?.isOneShot() == true) {
            return chain.proceed(request)
        }

        val urlStr = request.url.toString()
        val shouldRelay = config.relayedEndpoints.any { matchesCategory(urlStr, it) }
        if (!shouldRelay) {
            return chain.proceed(request)
        }

        val candidates = getCandidateRelayers(config)
        if (candidates.isEmpty()) {
            return chain.proceed(request)
        }

        var directException: Exception? = null
        var directFailureResponse: Response? = null
        try {
            val directResponse = chain.withConnectTimeout(DIRECT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS).proceed(request)
            if (directResponse.isSuccessful || directResponse.code !in config.failoverCodes) {
                return directResponse
            }
            AppLogger.i("Relayer", "Direct connection returned status ${directResponse.code}, starting relayer failover")
            directFailureResponse = directResponse
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.i("Relayer", "Direct connection failed (${e.message ?: e::class.java.simpleName}), starting relayer failover")
            directException = e
        }

        for (relayerUrl in candidates) {
            try {
                AppLogger.i("Relayer", "Attempting request via $relayerUrl")
                val relayed = buildRelayedRequest(request, relayerUrl)
                val response = chain.proceed(relayed)
                if (response.code !in config.failoverCodes) {
                    AppLogger.i("Relayer", "Request succeeded via $relayerUrl (status ${response.code})")
                    directFailureResponse?.close()
                    return response
                }
                AppLogger.w("Relayer", "Relayer $relayerUrl returned failover status ${response.code}")
                response.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.w("Relayer", "Relayer $relayerUrl failed (${e.message ?: e::class.java.simpleName})")
            }
        }

        AppLogger.w("Relayer", "All candidate relayers failed for request")
        if (directFailureResponse != null) {
            return directFailureResponse
        }
        if (directException != null) {
            when (directException) {
                is IOException -> throw directException
                is RuntimeException -> throw directException
                else -> throw IOException(directException)
            }
        }
        return chain.proceed(request)
    }

    private fun getCandidateRelayers(config: RelayerConfig): List<String> {
        val provider = config.provider.trim()

        if (provider.equals(SettingsStore.RELAYER_CUSTOM, ignoreCase = true)) {
            val custom = config.customUrl.trim()
            return if (custom.isNotBlank() && SettingsStore.isValidRelayerUrl(custom)) {
                listOf(custom)
            } else {
                emptyList()
            }
        }

        if (provider.equals(SettingsStore.RELAYER_AUTOMATIC, ignoreCase = true)) {
            val list = mutableListOf<String>()
            config.availableRelayers.values.forEach { url ->
                val trimmed = url.trim()
                if (trimmed.isNotBlank() && !list.contains(trimmed)) list.add(trimmed)
            }
            return list
        }

        val match = config.availableRelayers.entries.firstOrNull {
            it.key.equals(provider, ignoreCase = true)
        }
        if (match != null && match.value.isNotBlank()) {
            return listOf(match.value.trim())
        } else if (SettingsStore.isValidRelayerUrl(provider)) {
            return listOf(provider)
        }

        return emptyList()
    }

    private fun buildRelayedRequest(originalRequest: Request, relayerBaseUrl: String): Request {
        val originalUrl = originalRequest.url.toString()
        val b64 = Base64.encodeToString(
            originalUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        ).trim()
        val rawBase = relayerBaseUrl.trim()
        val base = if (rawBase.startsWith("http://", ignoreCase = true) || rawBase.startsWith("https://", ignoreCase = true)) {
            rawBase
        } else {
            "https://$rawBase"
        }
        val relayerUrl = when {
            base.endsWith("?url=") -> "$base$b64"
            base.endsWith("&url=") -> "$base$b64"
            base.contains("?") -> "${base}&url=$b64"
            else -> "${base.trimEnd('/')}/?url=$b64"
        }
        return originalRequest.newBuilder()
            .url(relayerUrl)
            .build()
    }

    private fun matchesCategory(urlStr: String, category: String): Boolean {
        val ep = FoxyRemoteConfig.current.endpoints
        return when (category) {
            "guardian" ->
                urlStr == ep.guardian.token ||
                    urlStr == ep.guardian.status ||
                    urlStr == ep.guardian.activate ||
                    urlStr.contains("/api/v1/fpn/")
            "fxa" ->
                urlStr == ep.controlPlane.oauthToken ||
                    urlStr == ep.controlPlane.oauthFallback ||
                    urlStr == ep.controlPlane.authorization ||
                    urlStr == ep.controlPlane.recoveryStatus ||
                    urlStr == ep.controlPlane.sessionDestroy ||
                    urlStr.contains("accounts.firefox.com")
            "config" ->
                urlStr.contains("FoxyConfig") || urlStr.contains("foxy_config")
            "server_list" ->
                urlStr == ep.serverList || urlStr.contains("vpn-serverlist")
            else -> false
        }
    }
}
