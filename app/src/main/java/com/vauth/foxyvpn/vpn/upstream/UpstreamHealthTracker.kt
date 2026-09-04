package com.vauth.foxyvpn.vpn.upstream

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class UpstreamHealthTracker {

    enum class Verdict {

        TARGET_FAILURE,

        SESSION_UNHEALTHY,

        SESSION_UNAUTHENTICATED,
    }

    private val timeoutTargets = LinkedHashSet<String>()

    @Synchronized
    fun observeSuccess() {
        timeoutTargets.clear()
    }

    @Synchronized
    fun reset() {
        timeoutTargets.clear()
    }

    @Synchronized
    fun observeFailure(target: String, cause: Throwable?): Verdict {

        if (invalidatesSession(cause)) {
            timeoutTargets.clear()
            return Verdict.SESSION_UNAUTHENTICATED
        }

        if (!isSilence(cause)) return Verdict.TARGET_FAILURE

        timeoutTargets.add(target)
        if (timeoutTargets.size < MAX_DISTINCT_TIMEOUT_TARGETS) return Verdict.TARGET_FAILURE

        timeoutTargets.clear()
        return Verdict.SESSION_UNHEALTHY
    }

    private fun isSilence(cause: Throwable?): Boolean = when (cause) {
        null -> true
        is UpstreamConnectTimeoutException -> true
        is SocketTimeoutException -> true
        is InterruptedIOException -> true
        else -> false
    }

    private fun invalidatesSession(cause: Throwable?): Boolean {
        val rejection = cause as? UpstreamConnectRejectedException ?: return false
        return rejection.statusCode in SESSION_FATAL_STATUS_CODES
    }

    companion object {

        const val MAX_DISTINCT_TIMEOUT_TARGETS = 3

        val SESSION_FATAL_STATUS_CODES = setOf(401, 403, 407)

        val TARGET_UNREACHABLE_STATUS_CODES = setOf(502, 503, 504)
    }
}
