package com.vauth.foxyvpn.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import okhttp3.ResponseBody

internal fun InputStream.readBoundedUtf8(maximumBytes: Int): String {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > maximumBytes - output.size())
            throw IOException("Response exceeds the size limit")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

internal fun ResponseBody.readBoundedText(maximumBytes: Int = 1_048_576): String {
    require(maximumBytes > 0)
    if (contentLength() > maximumBytes) throw IOException("Response exceeds the size limit")
    return source().use { source ->
        if (source.request(maximumBytes.toLong() + 1))
            throw IOException("Response exceeds the size limit")
        source.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }
}
