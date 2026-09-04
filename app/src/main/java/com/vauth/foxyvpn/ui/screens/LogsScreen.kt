package com.vauth.foxyvpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.LogEntry
import com.vauth.foxyvpn.data.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: String, val levels: Set<LogLevel>) {
    ALL("All", setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)),
    INFO("Info", setOf(LogLevel.INFO)),
    PROBLEMS("Problems", setOf(LogLevel.WARN, LogLevel.ERROR)),
}

private suspend fun writeLogFile(context: Context): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(dir, "foxyvpn-logs-$stamp.txt")
    file.writeText(AppLogger.exportAsText())
    file
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val entries by AppLogger.entries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }

    val countsByFilter: Map<LogFilter, Int> = remember(entries) {
        LogFilter.entries.associateWith { option -> entries.count { it.level in option.levels } }
    }
    val visible: List<LogEntry> = remember(entries, filter) {
        if (filter == LogFilter.ALL) entries else entries.filter { it.level in filter.levels }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (visible.size == entries.size) "Logs (${entries.size})"
                        else "Logs (${visible.size}/${entries.size})",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (entries.isEmpty()) {
                            Toast.makeText(context, "No log entries yet", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        scope.launch {
                            val file = writeLogFile(context)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, file.name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Save or send FoxyVPN logs").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Save/share logs as a file")
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("FoxyVPN logs", AppLogger.exportAsText()))
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear logs")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in LogFilter.entries) {
                    val count = countsByFilter[option] ?: 0
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },

                        label = { Text("${option.label} ($count)") },
                    )
                }
            }

            if (visible.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        when {
                            entries.isEmpty() -> "No log entries yet."
                            filter == LogFilter.PROBLEMS -> "No warnings or errors. Nothing has gone wrong."
                            else -> "Nothing matches this filter."
                        },
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible.asReversed()) { entry ->
                        val color = when (entry.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(
                                "${timeFormat.format(Date(entry.timestampMillis))} ${entry.level.name} [${entry.tag}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                            )
                            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
