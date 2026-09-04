package com.vauth.foxyvpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val EXIT_CHECK_URL = "https://www.cloudflare.com/cdn-cgi/trace"

private const val EXIT_CHECK_TIMEOUT_SECONDS = 15L

class ExitCheck {

    suspend fun verifyExitCountry(socksPort: Int, expectedCountryCode: String?): Result<String> =
        withContext(Dispatchers.IO) {

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(EXIT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(EXIT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(EXIT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            try {
                runCatching {
                    val request = Request.Builder().url(EXIT_CHECK_URL).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Exit check request failed: HTTP ${response.code}")
                        val body = response.body?.string().orEmpty()
                        val actualCountry = body.lineSequence()
                            .firstOrNull { it.startsWith("loc=") }
                            ?.removePrefix("loc=")
                            ?.trim()
                            .orEmpty()
                        if (actualCountry.isEmpty()) error("Exit check response did not report a location")

                        if (expectedCountryCode != null &&
                            !expectedCountryCode.equals("REC", ignoreCase = true) &&
                            !actualCountry.equals(expectedCountryCode, ignoreCase = true)
                        ) {
                            error("Exit country mismatch: expected $expectedCountryCode, got $actualCountry")
                        }
                        actualCountry
                    }
                }
            } finally {

                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
}
