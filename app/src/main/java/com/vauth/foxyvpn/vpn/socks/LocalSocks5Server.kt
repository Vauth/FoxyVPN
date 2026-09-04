package com.vauth.foxyvpn.vpn.socks

import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.vpn.RelayDispatchers
import com.vauth.foxyvpn.vpn.tun.HevSocks5TunnelConfig
import com.vauth.foxyvpn.vpn.upstream.UpstreamConnectRejectedException
import com.vauth.foxyvpn.vpn.upstream.UpstreamConnectTimeoutException
import com.vauth.foxyvpn.vpn.upstream.UpstreamHealthTracker
import com.vauth.foxyvpn.vpn.upstream.UpstreamSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "LocalSocks5Server"

private const val SESSION_WAIT_TIMEOUT_MS = 4_000L
private const val SESSION_WAIT_POLL_INTERVAL_MS = 100L

private const val OPEN_STREAM_TIMEOUT_MS = 10_000L

private const val HANDSHAKE_TIMEOUT_MS = 10_000

private const val HALF_CLOSE_DRAIN_TIMEOUT_MS = 2 * 60_000L

private const val MAX_CONCURRENT_CLIENT_CONNECTIONS = 512

private const val ACCEPT_BACKLOG = 128

private const val ACCEPT_ERROR_BACKOFF_MS = 100L

private const val RELAY_BUFFER_BYTES = 64 * 1024

private const val FAILURE_SUMMARY_INTERVAL_MS = 15_000L

private const val UNREACHABLE_TARGET_TTL_MS = 30_000L

private const val UNREACHABLE_TARGET_CACHE_CAP = 256

class LocalSocks5Server(
    private val bindAddress: String,
    private val port: Int,
    private val rejectFakeDnsAddresses: Boolean,
    private val sessionProvider: () -> UpstreamSession?,
    private val onSessionUnhealthy: () -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.w(
            TAG,
            "unhandled exception in a SOCKS5 connection coroutine (that connection was dropped; app keeps running)",
            throwable,
        )
    }
    private val scope = CoroutineScope(RelayDispatchers.relay + SupervisorJob() + exceptionHandler)

    private val connectionSlots = Semaphore(MAX_CONCURRENT_CLIENT_CONNECTIONS)

    private val upstreamFailures = AtomicInteger(0)
    private val lastFailureSummaryAt = AtomicLong(System.currentTimeMillis())

    private val staleFakeIpRejections = AtomicInteger(0)

    private val localNetworkRejections = AtomicInteger(0)
    private val cachedRefusalRejections = AtomicInteger(0)

    private val sessionAuthRejections = AtomicInteger(0)

    @Volatile private var unauthenticatedSession: UpstreamSession? = null

    private val health = UpstreamHealthTracker()

    private val unreachableTargets = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > UNREACHABLE_TARGET_CACHE_CAP
    }

    fun start() {

        val server = ServerSocket(port, ACCEPT_BACKLOG, InetAddress.getByName(bindAddress))
        serverSocket = server
        scope.launch {
            while (!server.isClosed) {
                val accepted = runCatching { server.accept() }
                val client = accepted.getOrNull()
                if (client == null) {

                    if (server.isClosed) break
                    AppLogger.d(
                        TAG,
                        "accept() failed on an open listening socket: " +
                            "${accepted.exceptionOrNull()?.message}",
                    )
                    delay(ACCEPT_ERROR_BACKOFF_MS)
                    continue
                }

                runCatching { client.tcpNoDelay = true }
                if (!connectionSlots.tryAcquire()) {
                    AppLogger.w(TAG, "rejecting SOCKS5 client: connection limit ($MAX_CONCURRENT_CLIENT_CONNECTIONS) reached")
                    runCatching { client.close() }
                    continue
                }
                scope.launch {
                    try {
                        handleClient(client)
                    } finally {
                        connectionSlots.release()
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }

        DohDnsClient.shutdown()
        scope.cancel()
        synchronized(unreachableTargets) { unreachableTargets.clear() }
        health.reset()
        unauthenticatedSession = null
        val stale = staleFakeIpRejections.getAndSet(0)
        if (stale > 0) {
            AppLogger.i(
                TAG,
                "$stale connection(s) were refused during this session because they targeted a " +
                    "synthetic DNS address with no live mapping",
            )
        }
        val local = localNetworkRejections.getAndSet(0)
        if (local > 0) {
            AppLogger.i(
                TAG,
                "$local connection(s) to local-network addresses were refused on-device during this " +
                    "session (they were never sent through the tunnel)",
            )
        }
        val cached = cachedRefusalRejections.getAndSet(0)
        if (cached > 0) {
            AppLogger.i(
                TAG,
                "$cached connection(s) were refused immediately during this session because the edge had " +
                    "recently refused the same destination (saving one round trip each)",
            )
        }
        val unauthenticated = sessionAuthRejections.getAndSet(0)
        if (unauthenticated > 0) {
            AppLogger.i(
                TAG,
                "$unauthenticated connection(s) were refused on-device during this session while the " +
                    "tunnel's proxy pass was being rejected by the edge (each one saved a round trip " +
                    "that could only have returned another 401)",
            )
        }

        val failures = upstreamFailures.getAndSet(0)
        if (failures > 0) {
            AppLogger.w(TAG, "$failures connection(s) failed upstream before this session ended")
        }
    }

    private suspend fun awaitUsableSession(): UpstreamSession? {
        val deadline = System.currentTimeMillis() + SESSION_WAIT_TIMEOUT_MS
        while (true) {
            val session = sessionProvider()
            if (session != null && session.isConnected) return session
            if (System.currentTimeMillis() >= deadline) return null
            delay(SESSION_WAIT_POLL_INTERVAL_MS)
        }
    }

    private fun recordUpstreamFailure(target: String, cause: Throwable?) {
        AppLogger.d(TAG, "opening upstream stream failed for $target: ${cause?.message ?: "timed out"}")
        upstreamFailures.incrementAndGet()
        val now = System.currentTimeMillis()
        val since = lastFailureSummaryAt.get()
        if (now - since < FAILURE_SUMMARY_INTERVAL_MS) return
        if (!lastFailureSummaryAt.compareAndSet(since, now)) return
        val total = upstreamFailures.getAndSet(0)
        if (total <= 0) return
        AppLogger.w(
            TAG,
            "$total connection(s) failed upstream in the last ${(now - since) / 1_000}s " +
                "(most recent: $target -- ${cause?.message ?: "timed out"})",
        )
    }

    private suspend fun handleClient(client: Socket) {
        try {
            handleClientOrThrow(client)
        } catch (e: EOFException) {

            AppLogger.d(TAG, "SOCKS5 client disconnected before completing its request (EOF)")
        } catch (e: SocketException) {
            AppLogger.d(TAG, "SOCKS5 client socket closed unexpectedly: ${e.message}")
        } catch (e: IOException) {
            AppLogger.d(TAG, "I/O error handling SOCKS5 client: ${e.message}")
        }
    }

    private suspend fun handleClientOrThrow(client: Socket) {
        client.use { socket ->

            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val ver = input.readUnsignedByte()
            if (ver != 0x05) {

                AppLogger.d(TAG, "rejecting client: unsupported SOCKS version $ver in greeting")
                return
            }
            val nMethods = input.readUnsignedByte()

            val methods = ByteArray(nMethods)
            if (nMethods > 0) input.readFully(methods)

            if (methods.none { it.toInt() == 0x00 }) {
                output.write(byteArrayOf(0x05, 0xFF.toByte()))
                output.flush()
                return
            }
            output.write(byteArrayOf(0x05, 0x00)) 
            output.flush()

            val reqVer = input.readUnsignedByte()

            if (reqVer != 0x05) {
                AppLogger.d(TAG, "rejecting request: unsupported SOCKS version $reqVer")
                output.write(socksReply(0x07))
                output.flush()
                return
            }
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() 

            socket.soTimeout = 0

            when (cmd) {
                0x01 -> handleConnect(socket, input, output)
                0x03 -> handleUdpAssociate(socket, input, output)
                else -> {
                    output.write(socksReply(0x07)) 
                    output.flush()
                }
            }
        }
    }

    private suspend fun handleConnect(socket: Socket, input: DataInputStream, output: OutputStream) {
        val target = runCatching { readTargetAddress(input) }.getOrNull()
        if (target == null) {
            output.write(socksReply(0x08)) 
            output.flush()
            return
        }
        val targetHost = target.host
        val targetPort = target.port
        val targetKey = "$targetHost:$targetPort"

        if (rejectFakeDnsAddresses && HevSocks5TunnelConfig.isFakeDnsAddress(targetHost)) {
            staleFakeIpRejections.incrementAndGet()
            AppLogger.d(
                TAG,
                "refusing $targetKey: synthetic DNS address with no live hostname mapping " +
                    "(stale cached DNS answer from before the tunnel restarted)",
            )
            output.write(socksReply(0x04)) 
            output.flush()
            return
        }

        val literal = target.literal
        if (literal != null && literal.isLocalNetworkDestination()) {
            localNetworkRejections.incrementAndGet()
            AppLogger.d(TAG, "refusing $targetKey: local-network address, not carried by the tunnel")
            output.write(socksReply(0x02)) 
            output.flush()
            return
        }

        if (isKnownUnreachable(targetKey)) {
            cachedRefusalRejections.incrementAndGet()
            AppLogger.d(TAG, "refusing $targetKey: the edge refused this destination moments ago")
            output.write(socksReply(0x04)) 
            output.flush()
            return
        }

        val session = awaitUsableSession()
        if (session == null) {
            output.write(socksReply(0x05)) 
            output.flush()
            return
        }

        if (session === unauthenticatedSession) {
            sessionAuthRejections.incrementAndGet()
            AppLogger.d(
                TAG,
                "refusing $targetKey: the edge is rejecting this session's proxy pass, so no destination " +
                    "can succeed until the tunnel is redialed",
            )
            output.write(socksReply(0x01)) 
            output.flush()
            return
        }

        val tunnelResult = runCatching {
            withTimeoutOrNull(OPEN_STREAM_TIMEOUT_MS) { session.openStream(targetHost, targetPort) }
        }
        val tunneled = tunnelResult.getOrNull()
        if (tunneled == null) {

            val cause = tunnelResult.exceptionOrNull()
            recordUpstreamFailure(targetKey, cause)

            if (shouldRememberRefusal(cause)) rememberUnreachable(targetKey)

            when (health.observeFailure(targetKey, cause)) {
                UpstreamHealthTracker.Verdict.TARGET_FAILURE -> Unit

                UpstreamHealthTracker.Verdict.SESSION_UNHEALTHY -> {
                    AppLogger.w(
                        TAG,
                        "upstream session looks unhealthy: several unrelated destinations failed without a " +
                            "single success between them, so the tunnel is the likelier cause than any one " +
                            "destination; asking for a redial",
                    )
                    runCatching { onSessionUnhealthy() }
                }

                UpstreamHealthTracker.Verdict.SESSION_UNAUTHENTICATED -> {

                    val alreadyLatched = session === unauthenticatedSession
                    unauthenticatedSession = session
                    if (!alreadyLatched) {
                        AppLogger.w(
                            TAG,
                            "the edge rejected this session's proxy pass (${cause?.message}); refusing further " +
                                "flows on this session locally and asking for a redial with a fresh pass",
                        )
                    }
                    runCatching { onSessionUnhealthy() }
                }
            }

            output.write(socksReply(replyCodeFor(cause)))
            output.flush()
            return
        }

        health.observeSuccess()

        if (unauthenticatedSession != null && session !== unauthenticatedSession) {
            unauthenticatedSession = null
        }

        output.write(socksReply(0x00, socket.localAddress, socket.localPort))
        output.flush()

        val clientToUpstream = scope.launch {
            runCatching { socket.getInputStream().copyTo(tunneled.output, RELAY_BUFFER_BYTES) }

            runCatching { tunneled.output.close() }
        }

        runCatching { tunneled.input.copyTo(output, RELAY_BUFFER_BYTES) }

        runCatching { socket.shutdownOutput() }

        if (clientToUpstream.isActive) {
            val drained = withTimeoutOrNull(HALF_CLOSE_DRAIN_TIMEOUT_MS) { clientToUpstream.join() }
            if (drained == null) {
                AppLogger.d(TAG, "closing half-closed tunnel after drain timeout=${HALF_CLOSE_DRAIN_TIMEOUT_MS}ms")
                runCatching { socket.close() }
                runCatching { tunneled.close() }
                clientToUpstream.join()
            }
        }
        tunneled.close()
    }

    private suspend fun handleUdpAssociate(socket: Socket, input: DataInputStream, output: OutputStream) {
        val target = runCatching { readTargetAddress(input) }.getOrNull()
        if (target == null) {
            output.write(socksReply(0x08)) 
            output.flush()
            return
        }
        val requestedPort = target.port

        if (requestedPort != DNS_PORT && requestedPort != 0) {
            output.write(socksReply(0x02)) 
            output.flush()
            return
        }

        val session = awaitUsableSession()
        if (session == null) {
            output.write(socksReply(0x05)) 
            output.flush()
            return
        }

        if (session === unauthenticatedSession) {
            sessionAuthRejections.incrementAndGet()
            output.write(socksReply(0x01)) 
            output.flush()
            return
        }

        val relay = runCatching {
            Socks5UdpDnsRelay(
                socket.localAddress,
                InetSocketAddress(socket.localAddress, port),
                sessionProvider,
            ) { runCatching { socket.close() } }
        }.getOrNull()
        if (relay == null) {
            output.write(socksReply(0x01)) 
            output.flush()
            return
        }

        try {
            relay.start()
            output.write(socksReply(0x00, relay.boundAddress, relay.boundPort))
            output.flush()

            val drain = ByteArray(64)
            while (true) {
                val read = runCatching { input.read(drain) }.getOrDefault(-1)
                if (read < 0) break
            }
        } finally {
            relay.stop()
        }
    }

    private data class Socks5Target(
        val host: String,
        val port: Int,
        val literal: InetAddress?,
    )

    private fun readTargetAddress(input: DataInputStream): Socks5Target {
        val addrType = input.readUnsignedByte()
        var literal: InetAddress? = null
        val host = when (addrType) {
            0x01 -> { 
                val bytes = ByteArray(4)
                input.readFully(bytes)

                literal = InetAddress.getByAddress(bytes)
                bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> { 
                val len = input.readUnsignedByte()

                if (len == 0) error("empty SOCKS5 domain name")
                val bytes = ByteArray(len)
                input.readFully(bytes)
                String(bytes, Charsets.US_ASCII)
            }
            0x04 -> { 
                val bytes = ByteArray(16)
                input.readFully(bytes)
                val parsed = InetAddress.getByAddress(bytes)
                literal = parsed
                parsed.hostAddress ?: ""
            }
            else -> error("unsupported SOCKS5 address type $addrType")
        }
        val port = input.readUnsignedShort()
        return Socks5Target(host, port, literal)
    }

    private fun InetAddress.isLocalNetworkDestination(): Boolean {
        if (isAnyLocalAddress ||
            isLoopbackAddress ||
            isLinkLocalAddress ||
            isMulticastAddress ||
            isSiteLocalAddress
        ) {
            return true
        }
        val raw = address

        if (raw.size == 16) return (raw[0].toInt() and 0xFE) == 0xFC

        if (raw.size == 4) return raw.all { it.toInt() and 0xFF == 0xFF }
        return false
    }

    private fun replyCodeFor(cause: Throwable?): Int {

        if (cause == null || cause is UpstreamConnectTimeoutException) {
            return 0x06 
        }
        val rejection = cause as? UpstreamConnectRejectedException ?: return 0x01
        val status = rejection.statusCode ?: return 0x01
        return when (status) {

            in UpstreamHealthTracker.TARGET_UNREACHABLE_STATUS_CODES -> 0x04 

            in UpstreamHealthTracker.SESSION_FATAL_STATUS_CODES -> 0x01 
            else -> 0x01
        }
    }

    private fun shouldRememberRefusal(cause: Throwable?): Boolean {
        val status = (cause as? UpstreamConnectRejectedException)?.statusCode ?: return false
        return status in UpstreamHealthTracker.TARGET_UNREACHABLE_STATUS_CODES
    }

    private fun isKnownUnreachable(key: String): Boolean = synchronized(unreachableTargets) {
        val expiresAt = unreachableTargets[key] ?: return false
        if (System.currentTimeMillis() >= expiresAt) {
            unreachableTargets.remove(key)
            return false
        }
        return true
    }

    private fun rememberUnreachable(key: String) {
        synchronized(unreachableTargets) {
            unreachableTargets[key] = System.currentTimeMillis() + UNREACHABLE_TARGET_TTL_MS
        }
    }

    private fun socksReply(
        replyCode: Int,
        boundAddress: InetAddress? = null,
        boundPort: Int = 0,
    ): ByteArray {
        val addressBytes = boundAddress?.address ?: ByteArray(4)
        val addressType = if (addressBytes.size == 16) 0x04 else 0x01
        return byteArrayOf(0x05, replyCode.toByte(), 0x00, addressType.toByte()) +
            addressBytes +
            byteArrayOf((boundPort ushr 8).toByte(), boundPort.toByte())
    }
}
