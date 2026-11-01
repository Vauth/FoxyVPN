package com.vauth.foxyvpn.vpn

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient

internal fun localProxyClient(
    port: Int,
    username: String = "",
    password: String = "",
    timeoutSeconds: Long = 8,
): OkHttpClient {
    require(port in 1..65_535)
    return OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .proxyAuthenticator { _, response ->
            if (username.isBlank() || response.request.header("Proxy-Authorization") != null) null
            else
                response.request
                    .newBuilder()
                    .header(
                        "Proxy-Authorization",
                        Credentials.basic(username, password, Charsets.UTF_8),
                    )
                    .build()
        }
        .connectTimeout(minOf(timeoutSeconds, 5), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}
