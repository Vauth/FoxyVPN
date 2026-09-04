package com.vauth.foxyvpn.vpn.socks

import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.vpn.RelayDispatchers
import com.vauth.foxyvpn.vpn.upstream.UpstreamSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Socks5UdpDnsRelay"

internal const val DNS_PORT = 53

internal const val MAX_DNS_MESSAGE_BYTES = 4096

private const val SOCKS5_UDP_HEADER_MIN_BYTES = 10

private const val MAX_INFLIGHT_QUERIES = 32

private const val QUERY_TIMEOUT_MS = 10_000L

internal class Socks5UdpDnsRelay(
    private val bindAddress: InetAddress,
    private val socksProxy: InetSocketAddress?,
    private val sessionProvider: () -> UpstreamSession?,
    private val onUnsupportedFlow: () -> Unit = {},
) {

    private val socket = DatagramSocket(0, bindAddress)

    private val inflight = Semaphore(MAX_INFLIGHT_QUERIES)

    private val queriesOverHttps = AtomicInteger(0)
    private val queriesOverTcp = AtomicInteger(0)
    private val httpsFallbacks = AtomicInteger(0)
    private val nonDnsDropped = AtomicInteger(0)
    private val otherDropped = AtomicInteger(0)
    private val unsupportedFlowReported = AtomicBoolean(false)

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.d(TAG, "dropped a DNS datagram: ${throwable.message}")
        otherDropped.incrementAndGet()
    }
    private val scope = CoroutineScope(RelayDispatchers.relay + SupervisorJob() + exceptionHandler)

    val boundAddress: InetAddress get() = bindAddress

    val boundPort: Int get() = socket.localPort

    fun start() {
        scope.launch {
            val buffer = ByteArray(MAX_DNS_MESSAGE_BYTES + SOCKS5_UDP_HEADER_MIN_BYTES + 256)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: IOException) {
                    break 
                }

                val datagram = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val replyTo = InetSocketAddress(packet.address, packet.port)

                if (!inflight.tryAcquire()) {
                    otherDropped.incrementAndGet()
                    continue
                }
                scope.launch {
                    try {
                        relay(datagram, replyTo)
                    } finally {
                        inflight.release()
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { socket.close() }
        scope.cancel()
        val https = queriesOverHttps.get()
        val tcp = queriesOverTcp.get()
        val fallbacks = httpsFallbacks.get()
        val nonDns = nonDnsDropped.get()
        val other = otherDropped.get()

        if (https > 0 || tcp > 0 || nonDns > 0 || other > 0) {
            AppLogger.d(
                TAG,
                "UDP association closed: $https DNS queries over HTTPS, $tcp over plaintext TCP " +
                    "($fallbacks of them after HTTPS failed), $nonDns non-DNS datagrams dropped, " +
                    "$other other drops",
            )
        }
    }

    private suspend fun relay(datagram: ByteArray, replyTo: InetSocketAddress) {
        val request = parseRequest(datagram)
        if (request == null) {
            otherDropped.incrementAndGet()
            return
        }
        if (request.port != DNS_PORT) {

            nonDnsDropped.incrementAndGet()
            if (unsupportedFlowReported.compareAndSet(false, true)) {
                AppLogger.d(
                    TAG,
                    "refusing UDP association: first datagram targets port ${request.port}, not DNS " +
                        "(this tunnel carries no general UDP, so the app is told now rather than left waiting)",
                )
                runCatching { onUnsupportedFlow() }
            }
            return
        }

        val query = datagram.copyOfRange(request.payloadOffset, datagram.size)
        if (query.isEmpty() || query.size > MAX_DNS_MESSAGE_BYTES) {
            otherDropped.incrementAndGet()
            return
        }

        val session = sessionProvider()?.takeIf { it.isConnected }
        if (session == null) {
            otherDropped.incrementAndGet()
            return
        }

        val answer = resolve(session, request.host, query)
        if (answer == null) {
            otherDropped.incrementAndGet()
            return
        }

        val response = datagram.copyOfRange(0, request.payloadOffset) + answer
        runCatching { socket.send(DatagramPacket(response, response.size, replyTo)) }
            .onFailure { otherDropped.incrementAndGet() }
    }

    private suspend fun resolve(
        session: UpstreamSession,
        resolverHost: String,
        query: ByteArray,
    ): ByteArray? {
        if (socksProxy != null) {
            val endpoint = DohDnsClient.endpointFor(resolverHost)
            if (endpoint != null) {
                val answer = DohDnsClient.query(socksProxy, endpoint, query)
                if (answer != null) {
                    queriesOverHttps.incrementAndGet()
                    return answer
                }
                httpsFallbacks.incrementAndGet()
            }
        }
        val answer = queryOverTcp(session, resolverHost, query) ?: return null
        queriesOverTcp.incrementAndGet()
        return answer
    }

    private suspend fun queryOverTcp(
        session: UpstreamSession,
        resolverHost: String,
        query: ByteArray,
    ): ByteArray? = coroutineScope {
        val tunneled = runCatching { session.openStream(resolverHost, DNS_PORT) }.getOrNull()
            ?: return@coroutineScope null
        try {
            val reader = async {
                runCatching {
                    tunneled.output.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
                    tunneled.output.write(query)
                    tunneled.output.flush()

                    val input = DataInputStream(tunneled.input)
                    val length = input.readUnsignedShort()
                    if (length == 0 || length > MAX_DNS_MESSAGE_BYTES) {
                        throw IOException("implausible DNS-over-TCP response length $length")
                    }
                    val answer = ByteArray(length)
                    input.readFully(answer)
                    answer
                }.getOrNull()
            }
            val answer = withTimeoutOrNull(QUERY_TIMEOUT_MS) { reader.await() }
            if (answer == null) {

                runCatching { tunneled.close() }
                reader.join()
            }
            answer
        } finally {
            runCatching { tunneled.close() }
        }
    }

    private class Request(val payloadOffset: Int, val host: String, val port: Int)

    private fun parseRequest(datagram: ByteArray): Request? {
        if (datagram.size < SOCKS5_UDP_HEADER_MIN_BYTES) return null

        if (datagram[0].toInt() != 0 || datagram[1].toInt() != 0) return null

        if (datagram[2].toInt() != 0) return null

        var offset = 3
        val addressType = datagram[offset++].toInt() and 0xFF
        val host = when (addressType) {
            0x01 -> {
                if (datagram.size < offset + 4 + 2) return null
                val bytes = datagram.copyOfRange(offset, offset + 4)
                offset += 4
                bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val length = datagram[offset++].toInt() and 0xFF
                if (length == 0) return null
                if (datagram.size < offset + length + 2) return null
                val name = String(datagram, offset, length, Charsets.US_ASCII)
                offset += length
                name
            }
            0x04 -> {
                if (datagram.size < offset + 16 + 2) return null
                val bytes = datagram.copyOfRange(offset, offset + 16)
                offset += 16
                InetAddress.getByAddress(bytes).hostAddress ?: return null
            }
            else -> return null
        }
        val port = ((datagram[offset].toInt() and 0xFF) shl 8) or (datagram[offset + 1].toInt() and 0xFF)
        offset += 2
        return Request(offset, host, port)
    }
}
