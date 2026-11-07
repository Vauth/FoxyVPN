package com.vauth.foxyvpn.data

import android.net.Network
import android.webkit.CookieManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps

fun Request.Builder.applyFirefoxHeaders(): Request.Builder = FirefoxAccountHeaders.apply(this)

class SimpleCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies
            .filter { it.matches(url) }
            .sortedByDescending { it.domain.length }
            .distinctBy { it.name }
    }

    @Synchronized fun snapshotAll(): List<Cookie> = cookies.toList()

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    @Synchronized
    fun syncToWebView(cookies: List<Cookie>) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        for (cookie in cookies) {
            val host = cookie.domain.trimStart('.')
            if (host.isEmpty()) continue
            val urlStr = "https://$host"
            val cookieString = StringBuilder().apply {
                append(cookie.name).append("=").append(cookie.value)
                append("; Domain=").append(cookie.domain)
                append("; Path=").append(cookie.path)
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
            }.toString()
            runCatching { manager.setCookie(urlStr, cookieString) }
        }
        runCatching { manager.flush() }
    }
}

private class ProtectingSocketFactory : SocketFactory() {

    private fun createProtectedSocket(
        localAddress: InetSocketAddress? = null,
        remoteAddress: InetSocketAddress? = null,
    ): Socket {
        val socket = Socket()
        try {
            socket.bind(localAddress ?: InetSocketAddress(0))
            val protector = ControlPlaneHttp.socketProtector
            if (protector != null && !protector(socket)) {
                throw java.io.IOException("VPN refused control-plane socket protection")
            }
            runCatching { ControlPlaneHttp.underlyingNetwork()?.bindSocket(socket) }
            if (remoteAddress != null) socket.connect(remoteAddress)
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    override fun createSocket(): Socket = createProtectedSocket()

    override fun createSocket(host: String, port: Int): Socket =
        createProtectedSocket(remoteAddress = InetSocketAddress(host, port))

    override fun createSocket(
        host: String,
        port: Int,
        localAddr: InetAddress,
        localPort: Int,
    ): Socket =
        createProtectedSocket(
            InetSocketAddress(localAddr, localPort),
            InetSocketAddress(host, port),
        )

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createProtectedSocket(remoteAddress = InetSocketAddress(host, port))

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localAddr: InetAddress,
        localPort: Int,
    ): Socket =
        createProtectedSocket(
            InetSocketAddress(localAddr, localPort),
            InetSocketAddress(host, port),
        )
}

object ControlPlaneHttp {
    val cookieJar = SimpleCookieJar()

    @Volatile var socketProtector: ((Socket) -> Boolean)? = null

    @Volatile var networkProvider: (() -> Network?)? = null

    @Volatile var bootstrapDnsConfigProvider: (() -> Pair<SettingsStore.DnsProvider, String>)? = null

    internal fun underlyingNetwork(): Network? {
        val provider = networkProvider ?: return null
        return provider() ?: throw java.io.IOException("No underlying network is available")
    }

    private val dohBootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .socketFactory(ProtectingSocketFactory())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var cachedDohUrl: String? = null
    @Volatile private var cachedDnsOverHttps: DnsOverHttps? = null
    private val dohLock = Any()

    private fun getOrCreateDoh(dohUrl: String): DnsOverHttps? {
        if (cachedDohUrl == dohUrl) {
            val cached = cachedDnsOverHttps
            if (cached != null) return cached
        }
        return synchronized(dohLock) {
            if (cachedDohUrl == dohUrl) {
                val cached = cachedDnsOverHttps
                if (cached != null) return@synchronized cached
            }
            val httpUrl = dohUrl.toHttpUrlOrNull() ?: return@synchronized null
            val host = httpUrl.host.removeSurrounding("[", "]")
            val bootstrapHosts = mutableListOf<InetAddress>()
            if (SettingsStore.isValidIpAddress(host)) {
                runCatching { InetAddress.getByName(host) }.getOrNull()?.let { bootstrapHosts.add(it) }
            } else {
                when {
                    host.equals("dns.google", ignoreCase = true) -> {
                        bootstrapHosts.add(InetAddress.getByName("8.8.8.8"))
                        bootstrapHosts.add(InetAddress.getByName("8.8.4.4"))
                    }
                    host.equals("cloudflare-dns.com", ignoreCase = true) ||
                        host.equals("one.one.one.one", ignoreCase = true) -> {
                        bootstrapHosts.add(InetAddress.getByName("1.1.1.1"))
                        bootstrapHosts.add(InetAddress.getByName("1.0.0.1"))
                    }
                    host.equals("dns.quad9.net", ignoreCase = true) -> {
                        bootstrapHosts.add(InetAddress.getByName("9.9.9.9"))
                        bootstrapHosts.add(InetAddress.getByName("149.112.112.112"))
                    }
                }
            }
            val builder =
                DnsOverHttps.Builder()
                    .client(dohBootstrapClient)
                    .url(httpUrl)
                    .post(true)
                    .includeIPv6(true)
            if (bootstrapHosts.isNotEmpty()) {
                builder.bootstrapDnsHosts(bootstrapHosts)
            }
            val doh = builder.build()
            cachedDohUrl = dohUrl
            cachedDnsOverHttps = doh
            doh
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .cookieJar(cookieJar)
            .socketFactory(ProtectingSocketFactory())
            .dns(
                object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (SettingsStore.isValidIpAddress(hostname)) {
                            return listOf(InetAddress.getByName(hostname))
                        }
                        val config = bootstrapDnsConfigProvider?.invoke()
                        val provider = config?.first ?: SettingsStore.DnsProvider.DIRECT_SYSTEM
                        val dohUrl = config?.second.orEmpty()
                        if (provider != SettingsStore.DnsProvider.DIRECT_SYSTEM && dohUrl.isNotBlank()) {
                            val doh = getOrCreateDoh(dohUrl)
                            if (doh != null) {
                                return doh.lookup(hostname)
                            }
                        }
                        val network = runCatching { underlyingNetwork() }.getOrNull()
                        val fromNetwork =
                            if (network != null) {
                                runCatching { network.getAllByName(hostname).toList() }.getOrNull()
                            } else {
                                null
                            }
                        return if (!fromNetwork.isNullOrEmpty()) {
                            fromNetwork
                        } else {
                            okhttp3.Dns.SYSTEM.lookup(hostname)
                        }
                    }
                }
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
