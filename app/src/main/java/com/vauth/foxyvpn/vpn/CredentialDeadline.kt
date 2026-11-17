package com.vauth.foxyvpn.vpn

internal fun credentialDeadlineMillis(
    expirySeconds: Long?,
    nowWallMillis: Long,
    nowElapsedMillis: Long,
): Long {
    val defaultLifetime = 10 * 60_000L
    val maximumAge = 24 * 60 * 60_000L
    val expiryMillis =
        expirySeconds?.let { seconds ->
            when {
                seconds <= 0 -> 0L
                seconds > Long.MAX_VALUE / 1000L -> Long.MAX_VALUE
                else -> seconds * 1000L
            }
        }
    val lifetime =
        if (expiryMillis == null) defaultLifetime
        else if (expiryMillis <= nowWallMillis) 0L
        else (expiryMillis - nowWallMillis).coerceIn(0L, maximumAge)
    return if (nowElapsedMillis > Long.MAX_VALUE - lifetime) Long.MAX_VALUE
    else nowElapsedMillis + lifetime
}
