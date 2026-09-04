package com.vauth.foxyvpn.vpn.upstream

import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.ControlPlaneHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "EdgeAddressResolver"

private const val DNS_MESSAGE_CONTENT_TYPE = "application/dns-message"
private val dnsMessageMediaType = DNS_MESSAGE_CONTENT_TYPE.toMediaType()

private const val DOH_PATH = "/dns-query"

private const val DNS_QUERY_ID = 0
private const val DNS_FLAGS_RECURSION_DESIRED = 0x0100
private const val DNS_HEADER_BYTES = 12
private const val DNS_QUESTION_TRAILER_BYTES = 4
private const val DNS_RECORD_HEADER_BYTES = 10
private const val DNS_TYPE_A = 1
private const val DNS_TYPE_AAAA = 28
private const val DNS_CLASS_IN = 1
private const val DNS_RCODE_MASK = 0x0F
private const val DNS_LABEL_MAX_BYTES = 63
private const val DNS_NAME_MAX_BYTES = 255
private const val DNS_COMPRESSION_POINTER_MASK = 0xC0
private const val IPV4_ADDRESS_BYTES = 4
private const val IPV6_ADDRESS_BYTES = 16
private const val ASCII_LIMIT = 128

private const val CONNECT_TIMEOUT_SECONDS = 3L
private const val CALL_TIMEOUT_SECONDS = 3L

private const val MAX_RESPONSE_BYTES = 4096
private const val READ_CHUNK_BYTES = 2048

private const val CACHE_TTL_MS = 5 * 60_000L

private const val FAILURE_BACKOFF_MS = 5 * 60_000L

internal object EdgeAddressResolver {

    private class CacheEntry(val addresses: List<String>, val resolvedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val suppressedUntilMs = ConcurrentHashMap<String, Long>()

    private val client: OkHttpClient by lazy {
        ControlPlaneHttp.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolve(hostname: String, endpointAddresses: List<String>): String? {
        if (endpointAddresses.isEmpty()) return null

        val host = hostname.trim().lowercase()
        if (host.isEmpty()) return null

        if (host.any { it.code >= ASCII_LIMIT }) return null

        if (isAddressLiteral(host)) return null

        cache[host]?.let { cached ->
            if (System.currentTimeMillis() - cached.resolvedAtMs < CACHE_TTL_MS) {
                return cached.addresses.firstOrNull()
            }
        }

        val endpoints = endpointAddresses
            .mapNotNull { endpointUrlFor(it) }
            .distinct()
            .filterNot { isSuppressed(it) }
        if (endpoints.isEmpty()) {

            AppLogger.d(TAG, "skipping DNS-over-HTTPS for $host; every configured endpoint is in backoff")
            return null
        }

        val addresses = queryEndpoints(host, endpoints)
        if (addresses.isEmpty()) {
            AppLogger.i(
                TAG,
                "could not resolve $host over DNS-over-HTTPS (tried ${endpoints.size} endpoint(s)); " +
                    "dialling by hostname via the system resolver",
            )
            return null
        }
        cache[host] = CacheEntry(addresses, System.currentTimeMillis())
        return addresses.first()
    }

    fun invalidate() {
        cache.clear()
    }

    private suspend fun queryEndpoints(host: String, endpoints: List<String>): List<String> = coroutineScope {
        val queryV4 = buildQuery(host, DNS_TYPE_A) ?: return@coroutineScope emptyList()
        val queryV6 = buildQuery(host, DNS_TYPE_AAAA) ?: return@coroutineScope emptyList()

        val inFlight = ConcurrentHashMap<String, Call>()

        val attempts = endpoints.map { endpoint ->

            endpoint to async(Dispatchers.IO) { queryOne(endpoint, queryV4, queryV6, inFlight) }
        }
        try {
            for ((endpoint, attempt) in attempts) {
                val addresses = attempt.await()
                if (addresses == null) {

                    suppressedUntilMs[endpoint] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                    continue
                }
                if (addresses.isEmpty()) {

                    continue
                }
                suppressedUntilMs.remove(endpoint)
                AppLogger.i(
                    TAG,
                    "resolved $host over DNS-over-HTTPS via $endpoint (${addresses.size} address(es))",
                )
                return@coroutineScope addresses
            }
            emptyList()
        } finally {
            inFlight.values.forEach { runCatching { it.cancel() } }
            attempts.forEach { (_, attempt) -> attempt.cancel() }
        }
    }

    private fun queryOne(
        endpoint: String,
        queryV4: ByteArray,
        queryV6: ByteArray,
        inFlight: ConcurrentHashMap<String, Call>,
    ): List<String>? {
        val v4Response = runCatching { post(endpoint, queryV4, inFlight) }.getOrElse { throwable ->
            AppLogger.d(TAG, "DoH endpoint $endpoint unusable: ${throwable.message}")
            return null
        }
        val v4 = parseAddresses(v4Response, DNS_TYPE_A)
        if (v4.isNotEmpty()) return v4

        val v6Response = runCatching { post(endpoint, queryV6, inFlight) }.getOrNull() ?: return null
        return parseAddresses(v6Response, DNS_TYPE_AAAA)
    }

    private fun post(endpoint: String, query: ByteArray, inFlight: ConcurrentHashMap<String, Call>): ByteArray {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", DNS_MESSAGE_CONTENT_TYPE)
            .post(query.toRequestBody(dnsMessageMediaType))
            .build()
        val call = client.newCall(request)
        inFlight[endpoint] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("DoH response had no body")
                return body.byteStream().readBounded(MAX_RESPONSE_BYTES)
            }
        } finally {
            inFlight.remove(endpoint, call)
        }
    }

    private fun isSuppressed(endpoint: String): Boolean =
        (suppressedUntilMs[endpoint] ?: 0L) > System.currentTimeMillis()

    private fun endpointUrlFor(address: String): String? {
        val host = address.trim().lowercase()
        if (!isAddressLiteral(host)) return null

        val authority = if (host.contains(':')) "[$host]" else host
        return "https://$authority$DOH_PATH"
    }
}

private fun buildQuery(hostname: String, type: Int): ByteArray? {
    val labels = hostname.split('.').filter { it.isNotEmpty() }
    if (labels.isEmpty()) return null

    var encodedNameBytes = 1 
    for (label in labels) {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        if (bytes.isEmpty() || bytes.size > DNS_LABEL_MAX_BYTES) return null
        encodedNameBytes += 1 + bytes.size
    }
    if (encodedNameBytes > DNS_NAME_MAX_BYTES) return null

    val out = ByteArrayOutputStream(DNS_HEADER_BYTES + encodedNameBytes + DNS_QUESTION_TRAILER_BYTES)
    fun writeShort(value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
    writeShort(DNS_QUERY_ID)
    writeShort(DNS_FLAGS_RECURSION_DESIRED)
    writeShort(1) 
    writeShort(0) 
    writeShort(0) 
    writeShort(0) 
    for (label in labels) {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        out.write(bytes.size)
        out.write(bytes, 0, bytes.size)
    }
    out.write(0)
    writeShort(type)
    writeShort(DNS_CLASS_IN)
    return out.toByteArray()
}

private fun parseAddresses(response: ByteArray, wantType: Int): List<String> {
    if (response.size < DNS_HEADER_BYTES) return emptyList()
    fun u8(index: Int): Int = response[index].toInt() and 0xFF
    fun u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    if (u8(3) and DNS_RCODE_MASK != 0) return emptyList()
    val questionCount = u16(4)
    val answerCount = u16(6)
    if (answerCount == 0) return emptyList()

    var offset = DNS_HEADER_BYTES
    for (i in 0 until questionCount) {
        offset = skipName(response, offset) ?: return emptyList()
        offset += DNS_QUESTION_TRAILER_BYTES
    }

    val expectedRdLength = if (wantType == DNS_TYPE_A) IPV4_ADDRESS_BYTES else IPV6_ADDRESS_BYTES
    val addresses = mutableListOf<String>()
    for (i in 0 until answerCount) {
        offset = skipName(response, offset) ?: return addresses
        if (offset + DNS_RECORD_HEADER_BYTES > response.size) return addresses
        val type = u16(offset)
        val rdLength = u16(offset + 8)
        offset += DNS_RECORD_HEADER_BYTES
        if (offset + rdLength > response.size) return addresses
        if (type == wantType && rdLength == expectedRdLength) {

            runCatching { InetAddress.getByAddress(response.copyOfRange(offset, offset + rdLength)) }
                .getOrNull()
                ?.hostAddress
                ?.let { addresses.add(it) }
        }
        offset += rdLength
    }
    return addresses
}

private fun skipName(message: ByteArray, start: Int): Int? {
    var offset = start
    var consumed = 0
    while (offset < message.size) {
        val length = message[offset].toInt() and 0xFF
        if (length == 0) return offset + 1
        if (length and DNS_COMPRESSION_POINTER_MASK == DNS_COMPRESSION_POINTER_MASK) {
            return if (offset + 2 <= message.size) offset + 2 else null
        }
        if (length > DNS_LABEL_MAX_BYTES) return null
        offset += 1 + length
        consumed += 1 + length
        if (consumed > DNS_NAME_MAX_BYTES) return null
    }
    return null
}

private fun isAddressLiteral(host: String): Boolean {
    if (host.contains(':')) return true
    val parts = host.split('.')
    if (parts.size != IPV4_ADDRESS_BYTES) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() <= 255
    }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_CHUNK_BYTES)
    while (out.size() <= limit) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    if (out.size() > limit) error("DoH response exceeded $limit bytes")
    return out.toByteArray()
}
