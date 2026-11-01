package com.vauth.foxyvpn.vpn

internal fun credentialDeadlineMillis(
    expirySeconds: Long?,
    nowWallMillis: Long,
    nowElapsedMillis: Long,
): Long {
    val maximumAge = 5 * 60_000L
    val expiryMillis =
        expirySeconds?.let { seconds ->
            when {
                seconds <= 0 -> 0L
                seconds > Long.MAX_VALUE / 1000L -> Long.MAX_VALUE
                else -> seconds * 1000L
            }
        }
    val lifetime =
        if (expiryMillis == null) maximumAge
        else if (expiryMillis <= nowWallMillis) 0L
        else (expiryMillis - nowWallMillis - 30_000L).coerceIn(0L, maximumAge)
    return if (nowElapsedMillis > Long.MAX_VALUE - lifetime) Long.MAX_VALUE
    else nowElapsedMillis + lifetime
}
