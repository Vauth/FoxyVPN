package com.vauth.foxyvpn.vpn

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

import kotlinx.coroutines.delay

internal object ListenerAvailability {
    suspend fun requireAvailable(
        listeners: List<Pair<String, Int>>,
        maxAttempts: Int = 5,
        delayMs: Long = 200L,
    ) {
        val distinctListeners = listeners.distinct()
        var lastException: BindException? = null
        var unavailablePort: Int? = null

        for (attempt in 0 until maxAttempts) {
            val reservations = mutableListOf<ServerSocket>()
            try {
                lastException = null
                unavailablePort = null
                for ((host, port) in distinctListeners) {
                    val socket = ServerSocket()
                    reservations.add(socket)
                    socket.reuseAddress = true
                    try {
                        socket.bind(InetSocketAddress(InetAddress.getByName(host), port))
                    } catch (error: BindException) {
                        lastException = error
                        unavailablePort = port
                        break
                    }
                }
                if (lastException == null) {
                    return
                }
            } finally {
                reservations.forEach { socket -> runCatching { socket.close() } }
            }

            if (attempt < maxAttempts - 1) {
                delay(delayMs)
            }
        }

        if (lastException != null && unavailablePort != null) {
            throw IllegalArgumentException(
                "Local port $unavailablePort is unavailable. Close the other listener or change the proxy port.",
                lastException,
            )
        }
    }

    suspend fun requireAvailable(
        ports: List<Int>,
        bindAddress: String = "127.0.0.1",
        maxAttempts: Int = 5,
        delayMs: Long = 200L,
    ) = requireAvailable(
        listeners = ports.map { bindAddress to it },
        maxAttempts = maxAttempts,
        delayMs = delayMs,
    )
}
