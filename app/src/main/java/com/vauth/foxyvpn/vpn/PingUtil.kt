package com.vauth.foxyvpn.vpn

import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.awaitResponse
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object PingUtil {

    suspend fun measureTcpLatencyMs(host: String, port: Int): Int? =
        withContext(Dispatchers.IO) {
            try {
                val start = System.nanoTime()
                val address =
                    ControlPlaneHttp.client.dns.lookup(host).firstOrNull()
                        ?: return@withContext null
                ControlPlaneHttp.client.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 1_500)
                }
                ((System.nanoTime() - start) / 1_000_000L)
                    .coerceIn(1, Int.MAX_VALUE.toLong())
                    .toInt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    suspend fun measureProxyLatencyMs(
        socksPort: Int,
        probeUrl: String,
        username: String = "",
        password: String = "",
    ): Int? =
        withContext(Dispatchers.IO) {
            require(socksPort in 1..65_535) { "Invalid local proxy port" }
            require(SettingsStore.isValidProbeUrl(probeUrl)) { "Latency endpoint must be HTTPS" }

            val client = localProxyClient(socksPort, username, password)
            try {
                val request =
                    Request.Builder()
                        .url(probeUrl)
                        .header("Cache-Control", "no-cache, no-store")
                        .build()
                val start = System.nanoTime()
                client.newCall(request).awaitResponse().use { response ->
                    check(response.isSuccessful) { "Latency probe failed: HTTP ${response.code}" }
                    ((System.nanoTime() - start) / 1_000_000L)
                        .coerceIn(1, Int.MAX_VALUE.toLong())
                        .toInt()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogger.d("PingUtil", "HTTPS latency probe failed: ${error.message}")
                null
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
}
