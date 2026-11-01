package com.vauth.foxyvpn.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import com.vauth.foxyvpn.data.AppLogger
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

class FoxyPlatformInterface(
    private val vpnService: FoxyVpnService,
    private val includeOnly: Boolean,
    private val onTunOpened: () -> Unit,
) : PlatformInterface, CommandServerHandler {

    companion object {
        private const val TAG = "FoxyPlatformInterface"
    }

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    fun closeTun() {
        runCatching { vpnInterface?.close() }
            .onFailure { AppLogger.w(TAG, "error closing VPN ParcelFileDescriptor", it) }
        vpnInterface = null
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {

        if (!vpnService.nativeProtectionRequired) return
        val protected = vpnService.protect(fd)
        if (!protected) {
            throw java.io.IOException("VPN refused native socket protection")
        }
    }

    override fun openTun(options: TunOptions): Int {
        AppLogger.i(TAG, "openTun called with MTU=${options.mtu}")
        val builder =
            vpnService.Builder().setSession("FoxyVPN").setMtu(options.mtu).setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        var hasIpv4 = false
        var hasIpv6 = false

        val inet4 = options.inet4Address
        if (inet4 != null) {
            while (inet4.hasNext()) {
                val prefix = inet4.next()
                builder.addAddress(prefix.address(), prefix.prefix())
                hasIpv4 = true
            }
        }

        val inet6 = options.inet6Address
        if (inet6 != null) {
            while (inet6.hasNext()) {
                val prefix = inet6.next()
                builder.addAddress(prefix.address(), prefix.prefix())
                hasIpv6 = true
            }
        }

        if (!hasIpv4 && !hasIpv6) {
            builder.addAddress("172.19.0.1", 30)
            hasIpv4 = true
        }

        if (options.autoRoute) {
            if (hasIpv4) {
                val routes = options.inet4RouteRange
                require(routes != null && routes.hasNext()) { "Missing IPv4 tunnel routes" }
                while (routes.hasNext()) {
                    val prefix = routes.next()
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
            }
            if (hasIpv6) {
                val routes = options.inet6RouteRange
                require(routes != null && routes.hasNext()) { "Missing IPv6 tunnel routes" }
                while (routes.hasNext()) {
                    val prefix = routes.next()
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
            }
            var addedDns = false
            val dnsIter = runCatching { options.dnsServerAddress }.getOrNull()
            if (dnsIter != null) {
                while (dnsIter.hasNext()) {
                    val dns = dnsIter.next()
                    if (dns.isNotBlank()) {
                        builder.addDnsServer(dns)
                        builder.addRoute(dns, if (':' in dns) 128 else 32)
                        addedDns = true
                    }
                }
            }
            if (!addedDns) {
                builder.addDnsServer("172.19.0.2")
                builder.addRoute("172.19.0.2", 32)
            }
        }

        if (includeOnly) {
            val included = options.includePackage
            var allowedCount = 0
            while (included != null && included.hasNext()) {
                val pkg = included.next()
                if (pkg.isBlank() || pkg == vpnService.packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                    allowedCount++
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    AppLogger.w(TAG, "included app is no longer installed: $pkg")
                }
            }

            require(allowedCount > 0) {
                "No included apps are available. Update split tunneling settings."
            }
        } else {
            val excluded = options.excludePackage
            val packages = mutableSetOf(vpnService.packageName)
            while (excluded != null && excluded.hasNext()) {
                excluded.next().takeIf { it.isNotBlank() }?.let { packages.add(it) }
            }
            packages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    AppLogger.w(TAG, "excluded app is no longer installed: $pkg")
                }
            }
        }

        vpnInterface?.close()
        val pfd =
            builder.establish()
                ?: error(
                    "Failed to establish Android VPN interface (Builder.establish() returned null)"
                )
        vpnInterface = pfd
        val fd = pfd.fd
        onTunOpened()
        return fd
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = vpnService.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                val uid =
                    runCatching {
                            cm.getConnectionOwnerUid(
                                ipProtocol,
                                InetSocketAddress(sourceAddress, sourcePort),
                                InetSocketAddress(destinationAddress, destinationPort),
                            )
                        }
                        .getOrDefault(Process.INVALID_UID)
                if (uid != Process.INVALID_UID) {
                    val packages =
                        runCatching { vpnService.packageManager.getPackagesForUid(uid) }.getOrNull()
                    val owner = ConnectionOwner()
                    owner.userId = uid
                    owner.userName = packages?.firstOrNull() ?: ""
                    return owner
                }
            }
        }
        throw java.io.FileNotFoundException("connection owner not found")
    }

    @Volatile private var defaultInterfaceListener: InterfaceUpdateListener? = null

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultInterfaceListener = listener
        notifyDefaultInterface()
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (defaultInterfaceListener == listener) {
            defaultInterfaceListener = null
        }
    }

    fun notifyDefaultInterface() {
        val listener = defaultInterfaceListener ?: return
        val cm = vpnService.getSystemService(ConnectivityManager::class.java) ?: return
        val activeNet = vpnService.firstUsableNetwork()
        if (activeNet == null) {

            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val caps = cm.getNetworkCapabilities(activeNet) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        val lp = cm.getLinkProperties(activeNet) ?: return
        val ifaceName = lp.interfaceName ?: return
        val nif =
            runCatching { NetworkInterface.getByName(ifaceName) }.getOrNull()
                ?: runCatching {
                        NetworkInterface.getNetworkInterfaces()?.toList()?.find {
                            it.name == ifaceName
                        }
                    }
                    .getOrNull()
        val index = nif?.index ?: return
        val isExpensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val isConstrained =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
        AppLogger.d(
            TAG,
            "reporting default interface to sing-box: $ifaceName index=$index expensive=$isExpensive",
        )
        runCatching {
                listener.updateDefaultInterface(ifaceName, index, isExpensive, isConstrained)
            }
            .onFailure { AppLogger.w(TAG, "updateDefaultInterface failed", it) }
    }

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm =
            vpnService.getSystemService(ConnectivityManager::class.java)
                ?: return emptyNetworkInterfaceIterator()
        val networks = runCatching { cm.allNetworks }.getOrNull() ?: emptyArray()
        val networkInterfaces =
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        val interfaces = mutableListOf<LibboxNetworkInterface>()

        for (network in networks) {
            val linkProperties = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val ifaceName = linkProperties.interfaceName ?: continue
            val nif = networkInterfaces.find { it.name == ifaceName } ?: continue

            val boxInterface =
                LibboxNetworkInterface().apply {
                    name = ifaceName
                    index = nif.index
                    dnsServer = StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress })
                    gateway =
                        StringArray(
                            linkProperties.routes
                                .filter { it.destination?.prefixLength == 0 }
                                .mapNotNull { it.gateway }
                                .filterNot { it.isAnyLocalAddress }
                                .mapNotNull { it.hostAddress }
                        )
                    type =
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                                Libbox.InterfaceTypeWIFI
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                                Libbox.InterfaceTypeCellular
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                                Libbox.InterfaceTypeEthernet
                            else -> Libbox.InterfaceTypeOther
                        }
                    mtu = runCatching { nif?.mtu }.getOrNull() ?: 1500
                    addresses =
                        StringArray(
                            nif?.interfaceAddresses?.mapNotNull { addr ->
                                runCatching {
                                        if (addr.address is Inet6Address) {
                                            "${Inet6Address.getByAddress(addr.address.address).hostAddress}/${addr.networkPrefixLength}"
                                        } else {
                                            "${addr.address.hostAddress}/${addr.networkPrefixLength}"
                                        }
                                    }
                                    .getOrNull()
                            } ?: linkProperties.linkAddresses.map { it.toString() }
                        )
                    var dumpFlags = 0
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        dumpFlags = dumpFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    }
                    if (nif?.isLoopback == true) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
                    if (nif?.isPointToPoint == true)
                        dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
                    if (runCatching { nif?.supportsMulticast() == true }.getOrDefault(false)) {
                        dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
                    }
                    flags = dumpFlags
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            interfaces.add(boxInterface)
        }

        return object : NetworkInterfaceIterator {
            private val iter = interfaces.iterator()

            override fun hasNext(): Boolean = iter.hasNext()

            override fun next(): LibboxNetworkInterface = iter.next()
        }
    }

    private fun emptyNetworkInterfaceIterator() =
        object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false

            override fun next(): LibboxNetworkInterface = error("no network interfaces")
        }

    private class StringArray(private val list: List<String>) : StringIterator {
        private val iterator = list.iterator()

        override fun len(): Int = list.size

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = if (iterator.hasNext()) iterator.next() else ""
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState {
        val wm = vpnService.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION") val info = runCatching { wm?.connectionInfo }.getOrNull()
        var ssid = info?.ssid ?: ""
        if (ssid == "<unknown ssid>") ssid = ""
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return WIFIState(ssid, info?.bssid ?: "")
    }

    override fun clearDNSCache() {}

    override fun sendNotification(notification: Notification) {}

    override fun cancelNotification(identifier: String, typeID: Int) {}

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun registerMyInterface(name: String) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {}

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = error("shell session not supported")

    override fun lookupUser(username: String): PlatformUser = error("user lookup not supported")

    override fun lookupSFTPServer(): String = error("sftp not supported")

    override fun readSystemSSHHostKey(): String = error("ssh not supported")

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession = error("bridge not supported")

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun createAutoRedirect(
        options: ByteArray,
        handler: AutoRedirectHandler,
    ): AutoRedirectSession = error("auto-redirect not supported")

    override fun localDNSTransport(): LocalDNSTransport =
        object : LocalDNSTransport {
            override fun raw(): Boolean = false

            override fun exchange(ctx: ExchangeContext, message: ByteArray) {
                ctx.errorCode(4)
            }

            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                val cleanDomain = domain.trimEnd('.')
                try {
                    val activeNet = vpnService.firstUsableNetwork()
                    if (activeNet == null) {
                        ctx.errorCode(2)
                        return
                    }
                    val addresses = activeNet.getAllByName(cleanDomain)
                    val filtered =
                        when (network) {
                            "ip4" -> addresses.filterIsInstance<Inet4Address>()
                            "ip6" -> addresses.filterIsInstance<Inet6Address>()
                            else -> addresses.toList()
                        }
                    ctx.success(filtered.joinToString("\n") { it.hostAddress.orEmpty() })
                } catch (e: Exception) {
                    AppLogger.d(TAG, "system lookup failed for $cleanDomain: ${e.message}")
                    ctx.errorCode(2)
                }
            }
        }

    override fun serviceStop() {
        vpnService.stopFromCore(this)
    }

    override fun serviceReload(): Unit =
        throw UnsupportedOperationException("Reconnect through FoxyVPN to reload settings")

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(enabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String) {
        when {
            message.contains("ERROR", ignoreCase = true) ||
                message.contains("FATAL", ignoreCase = true) ->
                AppLogger.e(TAG, "sing-box: $message")
            message.contains("WARN", ignoreCase = true) -> AppLogger.w(TAG, "sing-box: $message")
            else -> AppLogger.i(TAG, "sing-box: $message")
        }
    }

    override fun connectSSHAgent(): Int = -1
}
