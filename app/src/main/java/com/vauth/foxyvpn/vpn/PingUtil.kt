package com.vauth.foxyvpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

object PingUtil {
    private const val TIMEOUT_MS = 1_500

    suspend fun measureTcpLatencyMs(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TIMEOUT_MS.toLong()) {
            runCatching {
                val start = System.nanoTime()
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
                ((System.nanoTime() - start) / 1_000_000L).toInt()
            }.getOrNull()
        }
    }
}
