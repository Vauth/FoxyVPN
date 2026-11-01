package com.vauth.foxyvpn.vpn

internal class RecoveryBudget {
    private val attempts = java.util.ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first >= 15 * 60_000L) attempts.removeFirst()
        if (attempts.size >= 2) return false
        attempts.addLast(now)
        return true
    }
}

enum class TunnelHealth {
    UNKNOWN,
    CHECKING,
    HEALTHY,
    NO_NETWORK,
    UNREACHABLE,
}
