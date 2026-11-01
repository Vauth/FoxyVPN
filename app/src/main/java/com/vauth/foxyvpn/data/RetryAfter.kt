package com.vauth.foxyvpn.data

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.all { it in '0'..'9' }) {
        val seconds = raw.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
    }
    return runCatching {
            (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli() - nowMillis)
                .coerceAtLeast(0L)
        }
        .getOrNull()
}

internal fun retryDelayMillis(backoff: Long, serverHint: Long?, jitter: Long): Long {
    val base = maxOf(backoff, serverHint ?: 0L)
    return if (jitter > Long.MAX_VALUE - base) Long.MAX_VALUE else base + jitter
}

internal fun retryDeadlineMillis(nowElapsed: Long, delayMillis: Long): Long =
    if (delayMillis > Long.MAX_VALUE - nowElapsed) Long.MAX_VALUE else nowElapsed + delayMillis
