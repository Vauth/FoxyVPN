package com.vauth.foxyvpn.data

import android.util.Log
import com.vauth.foxyvpn.BuildConfig
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
}

data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

object AppLogger {

    private const val MAX_ENTRIES = 2_000
    private const val MIN_PUBLISH_INTERVAL_MS = 200L

    private val buffer = ArrayDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private var lastPublishAt =
        System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(MIN_PUBLISH_INTERVAL_MS)
    private var flushScheduled = false

    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AppLogger-flush").apply { isDaemon = true }
        }

    private val secretFields =
        Regex(
            """(?i)(["']?(?:access_?token|refresh_?token|session_?token|bearer_?token|proxy_?token|authPW|password|token|secret)["']?\s*[:=]\s*)(?:"(?:\\[\s\S]|[^"\\])*+"?|'(?:\\[\s\S]|[^'\\])*+'?|[^\s,}&]+)"""
        )
    private val authorization = Regex("(?i)(?:Bearer|Basic|Hawk)\\s+[^\\r\\n]+")
    private val cookies = Regex("(?i)(?:cookie|set-cookie)\\s*:\\s*[^\\r\\n]+")
    private val urlCredentials = Regex("""(?i)(\b(?:https?|socks5h?)://)[^\s/?#]*@""")

    internal fun redact(message: String): String {
        val withoutAuthorization = authorization.replace(message, "[redacted authorization]")
        val withoutCookies = cookies.replace(withoutAuthorization, "[redacted cookie]")
        val withoutUrlCredentials =
            urlCredentials.replace(withoutCookies) { "${it.groupValues[1]}[redacted]@" }
        return secretFields.replace(withoutUrlCredentials) { "${it.groupValues[1]}[redacted]" }
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, redact(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, redact(message))
        log(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, redact(if (error != null) "$message: ${error.message}" else message))
        log(LogLevel.WARN, tag, if (error != null) "$message: ${error.message}" else message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, redact(if (error != null) "$message: ${error.message}" else message))
        log(LogLevel.ERROR, tag, if (error != null) "$message: ${error.message}" else message)
    }

    private var entryCounter = 0L

    @Synchronized
    private fun log(level: LogLevel, tag: String, message: String) {
        buffer.addLast(
            LogEntry(
                ++entryCounter,
                System.currentTimeMillis(),
                level,
                tag,
                redact(message).take(8192),
            )
        )
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        schedulePublish()
    }

    private fun schedulePublish() {
        val now = System.nanoTime()
        val elapsedNanos = now - lastPublishAt
        val intervalNanos = TimeUnit.MILLISECONDS.toNanos(MIN_PUBLISH_INTERVAL_MS)
        if (elapsedNanos >= intervalNanos) {
            lastPublishAt = now
            _entries.value = buffer.toList()
            return
        }
        if (!flushScheduled) {
            flushScheduled = true
            val delayNanos = (intervalNanos - elapsedNanos).coerceAtLeast(0)
            val scheduledOk =
                runCatching {
                        scheduler.schedule(
                            {
                                synchronized(this) {
                                    flushScheduled = false
                                    lastPublishAt = System.nanoTime()
                                    _entries.value = buffer.toList()
                                }
                            },
                            delayNanos,
                            TimeUnit.NANOSECONDS,
                        )
                    }
                    .isSuccess
            if (!scheduledOk) flushScheduled = false
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    fun exportAsText(): String {
        val snapshot = synchronized(this) { buffer.toList() }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return snapshot.joinToString("\n") { entry ->
            val ts = formatter.format(Date(entry.timestampMillis))
            "$ts ${entry.level.name.padEnd(5)} [${entry.tag}] ${entry.message}"
        }
    }
}
