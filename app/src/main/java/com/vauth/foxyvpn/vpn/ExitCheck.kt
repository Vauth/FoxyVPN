package com.vauth.foxyvpn.vpn

import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.awaitResponse
import com.vauth.foxyvpn.data.readBoundedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val EXIT_CHECK_URL = "https://www.cloudflare.com/cdn-cgi/trace"

private const val EXIT_CHECK_TIMEOUT_SECONDS = 15L

data class IpLocationInfo(val ip: String, val countryCode: String)

class ExitCheck {

    suspend fun fetchDirectIpInfo(): Result<IpLocationInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                    val request = Request.Builder().url(EXIT_CHECK_URL).build()
                    ControlPlaneHttp.client.newCall(request).awaitResponse().use { response ->
                        check(response.isSuccessful) {
                            "Direct IP check failed: HTTP ${response.code}"
                        }
                        parseTrace(response.body?.readBoundedText(16 * 1024).orEmpty())
                    }
                }
                .onFailure { if (it is CancellationException) throw it }
        }

    suspend fun fetchProxiedIpInfo(
        socksPort: Int,
        username: String = "",
        password: String = "",
    ): Result<IpLocationInfo> =
        withContext(Dispatchers.IO) {
            val client = localProxyClient(socksPort, username, password, EXIT_CHECK_TIMEOUT_SECONDS)
            try {
                runCatching {
                        val request = Request.Builder().url(EXIT_CHECK_URL).build()
                        client.newCall(request).awaitResponse().use { response ->
                            if (!response.isSuccessful)
                                error("Proxied exit check failed: HTTP ${response.code}")
                            parseTrace(response.body?.readBoundedText(16 * 1024).orEmpty())
                        }
                    }
                    .onFailure { if (it is CancellationException) throw it }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    private fun parseTrace(body: String): IpLocationInfo {
        var ip = ""
        var loc = ""
        for (line in body.lineSequence()) {
            if (line.startsWith("ip=")) {
                ip = line.removePrefix("ip=").trim()
            } else if (line.startsWith("loc=")) {
                loc = line.removePrefix("loc=").trim()
            }
        }
        if (!SettingsStore.isValidIpAddress(ip)) {
            error("Trace response did not contain a valid IP address")
        }
        return IpLocationInfo(
            ip = ip,
            countryCode =
                loc.uppercase(java.util.Locale.ROOT)
                    .takeIf { it.length == 2 && it.all { letter -> letter in 'A'..'Z' } }
                    .orEmpty(),
        )
    }
}
