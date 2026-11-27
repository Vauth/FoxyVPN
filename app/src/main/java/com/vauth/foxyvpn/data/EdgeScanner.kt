package com.vauth.foxyvpn.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns

object EdgeScanner {
    const val MASQUE_HOST = "p.m1.fastly-masque.net"
    const val MASQUE_PORT = 2499
    const val RANGE_BASE = "23.235.42"
    const val HOST_COUNT = 254

    private const val CONNECT_TIMEOUT_MS = 1500
    private const val TLS_TIMEOUT_MS = 3000
    private const val MAX_CONCURRENT = 20

    @Volatile var upstreamProxyProvider: (() -> UpstreamProxyConfig?)? = null

    @Volatile var bootstrapDnsConfigProvider: (() -> Pair<SettingsStore.DnsProvider, String>)? = null

    @Volatile var networkProvider: (() -> android.net.Network?)? = null

    @Volatile var socketProtector: ((Socket) -> Boolean)? = null

    data class EdgeScanResult(val address: String, val ms: Int)

    private data class ProbeOutcome(val address: String, val tlsMs: Int, val httpMs: Int?)

    suspend fun scanBestEdge(onProgress: suspend (tested: Int, total: Int) -> Unit): EdgeScanResult? =
        withContext(Dispatchers.IO) {
            val proxy = proxyForScan() ?: return@withContext null
            val hosts = (1..HOST_COUNT).map { "$RANGE_BASE.$it" }
            val tested = AtomicInteger(0)
            val semaphore = Semaphore(MAX_CONCURRENT)
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            val outcomes = hosts.map { host ->
                async {
                    val result = semaphore.withPermit { probeEdge(host, sslContext, proxy) }
                    onProgress(tested.incrementAndGet(), hosts.size)
                    result
                }
            }.awaitAll().filterNotNull()
            val httpBest = outcomes.mapNotNull { outcome ->
                outcome.httpMs?.let { EdgeScanResult(outcome.address, it) }
            }.minByOrNull { it.ms }
            if (httpBest != null) return@withContext httpBest
            outcomes.minByOrNull { it.tlsMs }?.let { EdgeScanResult(it.address, it.tlsMs) }
        }

    private fun proxyForScan(): Proxy? {
        val config = upstreamProxyProvider?.invoke() ?: return Proxy.NO_PROXY
        if (config.host.isBlank() || config.port !in 1..65535) return Proxy.NO_PROXY
        val proxyType = if (config.type == SettingsStore.UpstreamProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS
        val address = resolveProxyHost(config.host) ?: return null
        return Proxy(proxyType, InetSocketAddress(address, config.port))
    }

    private fun resolveProxyHost(host: String): InetAddress? {
        if (SettingsStore.isValidIpAddress(host)) {
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
        val config = bootstrapDnsConfigProvider?.invoke()
        val provider = config?.first ?: SettingsStore.DnsProvider.DIRECT_SYSTEM
        val dohUrl = config?.second.orEmpty()
        if (provider != SettingsStore.DnsProvider.DIRECT_SYSTEM && dohUrl.isNotBlank()) {
            val doh = ControlPlaneHttp.getOrCreateDoh(dohUrl)
            if (doh != null) {
                return runCatching { doh.lookup(host).firstOrNull() }.getOrNull()
            }
        }
        val network = runCatching { networkProvider?.invoke() }.getOrNull()
        val fromNetwork = if (network != null) {
            runCatching { network.getAllByName(host).toList() }.getOrNull()
        } else {
            null
        }
        if (!fromNetwork.isNullOrEmpty()) return fromNetwork.first()
        return runCatching { Dns.SYSTEM.lookup(host).firstOrNull() }.getOrNull()
    }

    private fun probeEdge(host: String, sslContext: SSLContext, proxy: Proxy): ProbeOutcome? {
        val start = System.nanoTime()
        val plain = Socket(proxy)
        try {
            val protector = socketProtector
            if (protector != null && !protector(plain)) return null
            plain.connect(InetSocketAddress(host, MASQUE_PORT), CONNECT_TIMEOUT_MS)
            val sslSocket = sslContext.socketFactory.createSocket(plain, host, MASQUE_PORT, true) as SSLSocket
            sslSocket.soTimeout = TLS_TIMEOUT_MS
            return sslSocket.use {
                val params = it.sslParameters
                params.serverNames = listOf(SNIHostName(MASQUE_HOST))
                it.sslParameters = params
                it.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(MASQUE_HOST, it.session)) return@use null
                val tlsMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L).toInt()
                val httpMs = try {
                    val request = "GET / HTTP/1.1\r\nHost: $MASQUE_HOST\r\nConnection: close\r\n\r\n"
                    it.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                    it.outputStream.flush()
                    val buffer = ByteArray(5)
                    var read = 0
                    while (read < 5) {
                        val n = it.inputStream.read(buffer, read, 5 - read)
                        if (n == -1) return@use ProbeOutcome(host, tlsMs, null)
                        read += n
                    }
                    if (!String(buffer, Charsets.US_ASCII).startsWith("HTTP/")) return@use ProbeOutcome(host, tlsMs, null)
                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L).toInt()
                } catch (_: Exception) {
                    null
                }
                ProbeOutcome(host, tlsMs, httpMs)
            }
        } catch (_: Exception) {
            return null
        } finally {
            try {
                plain.close()
            } catch (_: Exception) {
            }
        }
    }
}
