package com.vauth.foxyvpn.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashFile(appContext, thread, throwable) }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Runtime.getRuntime().exit(1)
            }
        }
    }

    fun replayLastCrashIfAny(context: Context) {
        val file = crashFile(context)
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        if (text.isNullOrBlank()) return
        AppLogger.e(TAG, "app crashed last run; captured stack trace follows")
        text.lineSequence().forEach { line ->
            if (line.isNotBlank()) AppLogger.e(TAG, line)
        }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val writer = StringWriter()
        PrintWriter(writer).use { pw ->
            pw.println("Thread: ${thread.name}")
            throwable.printStackTrace(pw)
        }
        crashFile(context).writeText(writer.toString())
    }

    private fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)
}
