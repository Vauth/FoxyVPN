package com.vauth.foxyvpn.vpn.tun

import android.content.Context
import java.io.File

object HevSocks5TunnelConfig {
    const val FILE_NAME = "hev-socks5-tunnel.yaml"

    const val TUN_ADDRESS = "10.8.0.2"

    const val TUN_MTU = 8500

    private const val TUNNEL_ICMP_MODE = "reply"

    const val MAPDNS_ADDRESS = "198.18.0.2"
    private const val MAPDNS_PORT = 53

    private const val MAPDNS_NETWORK = "100.64.0.0"
    private const val MAPDNS_NETMASK = "255.192.0.0"

    private const val MAPDNS_NETWORK_BITS = 100L shl 24 or (64L shl 16)
    private const val MAPDNS_NETMASK_BITS = 255L shl 24 or (192L shl 16)

    private const val MAPDNS_CACHE_SIZE = 10_000

    private const val CONNECT_TIMEOUT_MS = 20_000

    private const val TCP_READ_WRITE_TIMEOUT_MS = 300_000

    private const val UDP_READ_WRITE_TIMEOUT_MS = 15_000

    private const val TCP_BUFFER_SIZE_BYTES = 65_536

    fun isFakeDnsAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        var packed = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            val octet = part.toIntOrNull() ?: return false
            if (octet !in 0..255) return false
            packed = (packed shl 8) or octet.toLong()
        }
        return (packed and MAPDNS_NETMASK_BITS) == MAPDNS_NETWORK_BITS
    }

    fun write(context: Context, socksPort: Int, customDnsServer: String? = null): String {

        val mapdnsBlock = if (customDnsServer == null) {
            """
            mapdns:
              address: '$MAPDNS_ADDRESS'
              port: $MAPDNS_PORT
              network: '$MAPDNS_NETWORK'
              netmask: '$MAPDNS_NETMASK'
              cache-size: $MAPDNS_CACHE_SIZE
            """.trimIndent() + "\n"
        } else {
            ""
        }

        val yaml = """
            tunnel:
              mtu: $TUN_MTU
              ipv4: '$TUN_ADDRESS'
              icmp: '$TUNNEL_ICMP_MODE'
            socks5:
              port: $socksPort
              address: '127.0.0.1'
              udp: 'udp'
            """.trimIndent() + "\n" + mapdnsBlock +
            """
            misc:
              connect-timeout: $CONNECT_TIMEOUT_MS
              tcp-read-write-timeout: $TCP_READ_WRITE_TIMEOUT_MS
              udp-read-write-timeout: $UDP_READ_WRITE_TIMEOUT_MS
              tcp-buffer-size: $TCP_BUFFER_SIZE_BYTES
              log-level: 'warn'
            """.trimIndent() + "\n"

        val file = File(context.filesDir, FILE_NAME)
        file.writeText(yaml)
        return file.absolutePath
    }
}
