package com.vauth.foxyvpn.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.LogLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LogFilter(val levels: Set<LogLevel>) {
    ALL(setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)),
    INFO(setOf(LogLevel.INFO)),
    PROBLEMS(setOf(LogLevel.WARN, LogLevel.ERROR)),
}

sealed interface LogsUserMessage {
    data class StringRes(val resId: Int) : LogsUserMessage
    data class PlainText(val text: String) : LogsUserMessage
}

data class LogsUiState(
    val filter: LogFilter = LogFilter.ALL,
    val isExporting: Boolean = false,
    val userMessage: LogsUserMessage? = null,
    val shareFileIntent: Intent? = null,
)

class LogsViewModel : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    fun setFilter(filter: LogFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun clearLogs() {
        AppLogger.clear()
    }

    fun exportLogs(context: Context, chooserTitle: String) {
        if (_state.value.isExporting) return
        _state.update { it.copy(isExporting = true) }
        val appContext = context.applicationContext

        viewModelScope.launch {
            try {
                val file = writeLogFile(appContext)
                val uri =
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file,
                    )
                val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                val chooser =
                    Intent.createChooser(shareIntent, chooserTitle).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                _state.update { it.copy(shareFileIntent = chooser, isExporting = false) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isExporting = false) }
                throw cancelled
            } catch (error: Exception) {
                AppLogger.w("LogsViewModel", "Could not export logs", error)
                _state.update {
                    it.copy(
                        isExporting = false,
                        userMessage = LogsUserMessage.StringRes(R.string.logs_share_failed),
                    )
                }
            }
        }
    }

    fun copyLogs(context: Context) {
        if (_state.value.isExporting) return
        _state.update { it.copy(isExporting = true) }
        val appContext = context.applicationContext

        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { AppLogger.exportAsText() }
                val clipboard =
                    appContext.getSystemService(ClipboardManager::class.java)
                        ?: error("Clipboard unavailable")
                val clip =
                    ClipData.newPlainText("FoxyVPN logs", text).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            description.extras =
                                PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                        }
                    }
                clipboard.setPrimaryClip(clip)
                val message =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        LogsUserMessage.StringRes(R.string.logs_copied)
                    } else {
                        null
                    }
                _state.update { it.copy(isExporting = false, userMessage = message) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isExporting = false) }
                throw cancelled
            } catch (error: Exception) {
                AppLogger.w("LogsViewModel", "Could not copy logs", error)
                _state.update {
                    it.copy(
                        isExporting = false,
                        userMessage = LogsUserMessage.StringRes(R.string.logs_copy_failed),
                    )
                }
            }
        }
    }

    fun consumeShareIntent() {
        _state.update { it.copy(shareFileIntent = null) }
    }

    fun consumeUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    companion object {
        private val exportMutex = Mutex()

        private suspend fun writeLogFile(context: Context): File =
            withContext(Dispatchers.IO) {
                exportMutex.withLock {
                    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
                    val now = System.currentTimeMillis()

                    dir.listFiles()
                        ?.filter {
                            it.isFile && it.name.startsWith("foxyvpn-logs-") && it.extension == "txt"
                        }
                        ?.sortedByDescending { it.lastModified() }
                        ?.forEachIndexed { index, old ->
                            if (index >= 9 || now - old.lastModified() >= 7 * 24 * 60 * 60_000L) {
                                old.delete()
                            }
                        }
                    val stamp =
                        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
                    val file = File(dir, "foxyvpn-logs-$stamp.txt")
                    file.writeText(AppLogger.exportAsText())
                    file
                }
            }
    }
}
