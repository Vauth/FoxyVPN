package com.vauth.foxyvpn.vpn.socks

import com.vauth.foxyvpn.data.AppLogger
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "DohDnsClient"

private const val DNS_MESSAGE_CONTENT_TYPE = "application/dns-message"
private val DNS_MESSAGE_MEDIA_TYPE = DNS_MESSAGE_CONTENT_TYPE.toMediaType()

private const val DOH_PATH = "/dns-query"

private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val CALL_TIMEOUT_SECONDS = 6L

private const val MAX_IDLE_CONNECTIONS = 2
private const val KEEP_ALIVE_MINUTES = 5L

private const val FAILURE_SUPPRESSION_THRESHOLD = 3
private const val FAILURE_SUPPRESSION_MS = 5 * 60_000L

internal object DohEndpoints {
    private class Operator(val addresses: List<String>)

    private val OPERATORS = listOf(

        Operator(
            listOf(
                "1.1.1.1",
                "1.0.0.1",
                "2606:4700:4700::1111",
                "2606:4700:4700::1001",
            ),
        ),

        Operator(
            listOf(
                "8.8.8.8",
                "8.8.4.4",
                "2001:4860:4860::8888",
                "2001:4860:4860::8844",
            ),
        ),

        Operator(
            listOf(
                "9.9.9.9",
                "149.112.112.112",
                "2620:fe::fe",
                "2620:fe::9",
            ),
        ),
    )

    private val endpoints: Map<String, String> = buildMap {
        for (operator in OPERATORS) {
            for (literal in operator.addresses) {
                val normalized = normalize(literal) ?: continue
                put(normalized, endpointUrl(literal))
            }
        }
    }

    private val operatorEndpoints: Map<String, List<String>> = buildMap {
        for (operator in OPERATORS) {
            val urls = operator.addresses.mapNotNull { literal ->
                normalize(literal)?.let { endpointUrl(literal) }
            }
            for (literal in operator.addresses) {
                val normalized = normalize(literal) ?: continue
                val own = endpointUrl(literal)
                put(normalized, listOf(own) + urls.filter { it != own })
            }
        }
    }

    fun forResolver(host: String): String? {
        val normalized = normalize(host) ?: return null
        return endpoints[normalized]
    }

    fun operatorEndpointsFor(host: String): List<String> {
        val normalized = normalize(host) ?: return emptyList()
        return operatorEndpoints[normalized] ?: emptyList()
    }

    private fun endpointUrl(literal: String): String {
        val authority = if (literal.contains(':')) "[$literal]" else literal
        return "https://$authority$DOH_PATH"
    }

    private fun normalize(host: String): String? {
        if (!isAddressLiteral(host)) return null
        return runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
    }

    private fun isAddressLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.contains(':')) {
            return host.all { it == ':' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }
}

internal object DohDnsClient {
    private class Transport(val proxyAddress: InetSocketAddress, val client: OkHttpClient)

    private class EndpointHealth {

        @Volatile var consecutiveFailures: Int = 0
        @Volatile var suppressedUntil: Long = 0L
    }

    @Volatile private var transport: Transport? = null

    private val health = ConcurrentHashMap<String, EndpointHealth>()

    fun endpointFor(resolverHost: String): String? {
        val preferred = DohEndpoints.forResolver(resolverHost) ?: return null
        if (!isSuppressed(preferred)) return preferred
        return DohEndpoints.operatorEndpointsFor(resolverHost)
            .firstOrNull { !isSuppressed(it) }
    }

    fun query(socksProxy: InetSocketAddress, endpointUrl: String, query: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(endpointUrl)
            .header("accept", DNS_MESSAGE_CONTENT_TYPE)

            .post(query.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
            .build()
        val answer = runCatching {
            clientFor(socksProxy).newCall(request).execute().use { readAnswer(endpointUrl, it) }
        }.getOrElse { throwable ->
            AppLogger.d(TAG, "DoH query to $endpointUrl failed: ${throwable.message}")
            null
        }
        if (answer == null) recordFailure(endpointUrl) else recordSuccess(endpointUrl)
        return answer
    }

    fun shutdown() {
        val existing = synchronized(this) { transport.also { transport = null } }
        health.clear()
        existing?.let { close(it.client) }
    }

    private fun isSuppressed(endpointUrl: String): Boolean {
        val state = health[endpointUrl] ?: return false
        return state.suppressedUntil > System.currentTimeMillis()
    }

    private fun readAnswer(endpointUrl: String, response: Response): ByteArray? {
        if (!response.isSuccessful) {
            AppLogger.d(TAG, "DoH endpoint $endpointUrl answered HTTP ${response.code}")
            return null
        }
        val body = response.body ?: return null

        val mediaType = body.contentType()
        if (mediaType != null && "${mediaType.type}/${mediaType.subtype}" != DNS_MESSAGE_CONTENT_TYPE) {
            AppLogger.d(TAG, "DoH endpoint $endpointUrl answered content-type $mediaType")
            return null
        }
        if (body.contentLength() > MAX_DNS_MESSAGE_BYTES) return null
        return readBounded(body.byteStream(), MAX_DNS_MESSAGE_BYTES)
    }

    private fun readBounded(stream: InputStream, limit: Int): ByteArray? {
        val buffer = ByteArray(limit + 1)
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        if (total == 0 || total > limit) return null
        return buffer.copyOf(total)
    }

    private fun clientFor(socksProxy: InetSocketAddress): OkHttpClient {
        transport?.takeIf { it.proxyAddress == socksProxy }?.let { return it.client }
        return synchronized(this) {
            val existing = transport
            if (existing != null && existing.proxyAddress == socksProxy) {
                existing.client
            } else {

                existing?.let { close(it.client) }
                val client = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, socksProxy))
                    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .connectionPool(
                        ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES),
                    )
                    .build()
                transport = Transport(socksProxy, client)
                client
            }
        }
    }

    private fun close(client: OkHttpClient) {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun recordSuccess(endpointUrl: String) {
        val state = health[endpointUrl] ?: return
        state.consecutiveFailures = 0
        state.suppressedUntil = 0L
    }

    private fun recordFailure(endpointUrl: String) {
        val state = health.computeIfAbsent(endpointUrl) { EndpointHealth() }
        val failures = state.consecutiveFailures + 1
        state.consecutiveFailures = failures
        if (failures < FAILURE_SUPPRESSION_THRESHOLD || state.suppressedUntil > System.currentTimeMillis()) return
        state.suppressedUntil = System.currentTimeMillis() + FAILURE_SUPPRESSION_MS
        AppLogger.w(
            TAG,
            "$endpointUrl failed $failures times in a row; leaving it alone for the next " +
                "${FAILURE_SUPPRESSION_MS / 60_000} minutes and using the same resolver's other addresses",
        )
    }
}
