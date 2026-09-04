package com.vauth.foxyvpn.data

import java.util.Locale

private const val UNIT = 1024.0
private val UNITS = listOf("KB", "MB", "GB", "TB", "PB")

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "\u2014"
    if (bytes < 1024) return "$bytes B"
    var value = bytes / UNIT
    var index = 0
    while (value >= UNIT && index < UNITS.lastIndex) {
        value /= UNIT
        index++
    }
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, UNITS[index])
}

fun formatBytesPerSecond(bytesPerSecond: Long): String =
    "${formatBytes(bytesPerSecond.coerceAtLeast(0))}/s"
