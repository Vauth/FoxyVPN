package com.vauth.foxyvpn.vpn.upstream

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface UpstreamSession : Closeable {

    suspend fun connect()

    suspend fun openStream(targetHost: String, targetPort: Int): TunneledStream

    fun updateBearerToken(token: String)

    val isConnected: Boolean
}

data class TunneledStream(
    val input: InputStream,
    val output: OutputStream,
    val close: () -> Unit,
)

class UpstreamConnectRejectedException(
    val statusCode: Int?,
    val authority: String,
    message: String,
) : IOException(message)

class UpstreamConnectTimeoutException(
    val authority: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
