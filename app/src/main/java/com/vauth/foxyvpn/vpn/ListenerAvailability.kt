package com.vauth.foxyvpn.vpn

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

internal object ListenerAvailability {
    fun requireAvailable(ports: List<Int>) {
        val reservations = mutableListOf<ServerSocket>()
        try {
            for (port in ports.distinct()) {
                val socket = ServerSocket()
                reservations.add(socket)
                socket.reuseAddress = false
                try {
                    socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                } catch (error: BindException) {
                    throw IllegalArgumentException(
                        "Local port $port is unavailable. Close the other listener or change the proxy port.",
                        error,
                    )
                }
            }
        } finally {
            reservations.forEach { socket -> runCatching { socket.close() } }
        }
    }
}
