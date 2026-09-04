package com.vauth.foxyvpn.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

object AppLogger {

    private const val MAX_ENTRIES = 2_000
    private const val MIN_PUBLISH_INTERVAL_MS = 200L

    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val lastPublishAt = AtomicLong(0L)
    private val flushScheduled = AtomicBoolean(false)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AppLogger-flush").apply { isDaemon = true }
    }

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        log(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
        log(LogLevel.WARN, tag, if (error != null) "$message: ${error.message}" else message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
        log(LogLevel.ERROR, tag, if (error != null) "$message: ${error.message}" else message)
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        buffer.addLast(LogEntry(System.currentTimeMillis(), level, tag, message))

        while (buffer.size > MAX_ENTRIES) {
            if (buffer.pollFirst() == null) break
        }
        schedulePublish()
    }

    private fun schedulePublish() {
        val now = System.currentTimeMillis()
        val last = lastPublishAt.get()
        if (now - last >= MIN_PUBLISH_INTERVAL_MS) {
            if (lastPublishAt.compareAndSet(last, now)) {
                _entries.value = buffer.toList()
            }
            return
        }
        if (flushScheduled.compareAndSet(false, true)) {
            val delayMs = (MIN_PUBLISH_INTERVAL_MS - (now - last)).coerceAtLeast(0)
            val scheduledOk = runCatching {
                scheduler.schedule({
                    flushScheduled.set(false)
                    lastPublishAt.set(System.currentTimeMillis())
                    _entries.value = buffer.toList()
                }, delayMs, TimeUnit.MILLISECONDS)
            }.isSuccess
            if (!scheduledOk) flushScheduled.set(false)
        }
    }

    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    fun exportAsText(): String = buffer.toList().joinToString("\n") { entry ->
        val ts = timeFormat.format(Date(entry.timestampMillis))
        "$ts ${entry.level.name.padEnd(5)} [${entry.tag}] ${entry.message}"
    }
}
