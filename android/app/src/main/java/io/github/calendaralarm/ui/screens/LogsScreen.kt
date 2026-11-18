package io.github.calendaralarm.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.ui.theme.CalGreen
import io.github.calendaralarm.ui.theme.CalGrey
import io.github.calendaralarm.ui.theme.CalRed
import io.github.calendaralarm.ui.theme.CalOrange
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val logs by AppLogger.entriesFlow.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var filterLevel by remember { mutableStateOf<AppLogger.LogEntry.Level?>(null) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    val filteredLogs = if (filterLevel != null) {
        logs.filter { it.level == filterLevel }
    } else {
        logs
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs (${logs.size})") },
                actions = {
                    // Filter
                    IconButton(onClick = {
                        filterLevel = when (filterLevel) {
                            null -> AppLogger.LogEntry.Level.ERROR
                            AppLogger.LogEntry.Level.ERROR -> AppLogger.LogEntry.Level.WARN
                            AppLogger.LogEntry.Level.WARN -> null
                            else -> null
                        }
                    }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter")
                    }
                    // Export
                    IconButton(onClick = {
                        val csv = AppLogger.exportToCsv()
                        // Copy to clipboard
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Calendar Alarm Logs", csv))
                        Toast.makeText(context, "Logs copied to clipboard (${logs.size} entries)", Toast.LENGTH_SHORT).show()

                        // Also save to file and share
                        try {
                            val logFile = File(context.cacheDir, "calendar-alarm-logs.csv")
                            logFile.writeText(csv)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export logs"))
                        } catch (e: Exception) {
                            // Clipboard copy already succeeded
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    // Clear
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Filter indicator
            if (filterLevel != null) {
                Surface(
                    color = CalOrange.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Filter: ${filterLevel!!.name} — tap filter button to change",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (logs.isEmpty()) "No logs yet" else "No logs with this filter",
                        color = CalGrey
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredLogs, key = { it.timestamp + it.tag + it.message.hashCode() }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

/**
 * Compact logs view for use in a dialog (from Settings).
 * Shows the latest logs without Scaffold/TopBar.
 */
@Composable
fun LogsScreenCompact() {
    val context = LocalContext.current
    val logs by AppLogger.entriesFlow.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var filterLevel by remember { mutableStateOf<AppLogger.LogEntry.Level?>(null) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    val filteredLogs = if (filterLevel != null) {
        logs.filter { it.level == filterLevel }
    } else {
        logs
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Toolbar row: filter, export, clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            // Filter
            IconButton(onClick = {
                filterLevel = when (filterLevel) {
                    null -> AppLogger.LogEntry.Level.ERROR
                    AppLogger.LogEntry.Level.ERROR -> AppLogger.LogEntry.Level.WARN
                    AppLogger.LogEntry.Level.WARN -> null
                    else -> null
                }
            }) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = "Filter",
                    tint = if (filterLevel != null) CalOrange else MaterialTheme.colorScheme.onSurface
                )
            }
            // Export
            IconButton(onClick = {
                val csv = AppLogger.exportToCsv()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Calendar Alarm Logs", csv))
                Toast.makeText(context, "Logs copied to clipboard (${logs.size} entries)", Toast.LENGTH_SHORT).show()

                try {
                    val logFile = File(context.cacheDir, "calendar-alarm-logs.csv")
                    logFile.writeText(csv)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        logFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Export logs"))
                } catch (e: Exception) {
                    // Clipboard copy already succeeded
                }
            }) {
                Icon(Icons.Default.Share, contentDescription = "Export")
            }
            // Clear
            IconButton(onClick = { AppLogger.clear() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = CalRed)
            }
        }

        // Filter indicator
        if (filterLevel != null) {
            Surface(
                color = CalOrange.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Filter: ${filterLevel!!.name}",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (logs.isEmpty()) "No logs yet" else "No logs with this filter",
                    color = CalGrey
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filteredLogs, key = { it.timestamp + it.tag + it.message.hashCode() }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: AppLogger.LogEntry) {
    val color = when (entry.level) {
        AppLogger.LogEntry.Level.DEBUG -> CalGrey
        AppLogger.LogEntry.Level.INFO -> CalGreen
        AppLogger.LogEntry.Level.WARN -> CalOrange
        AppLogger.LogEntry.Level.ERROR -> CalRed
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                entry.timestamp.take(19), // HH:mm:ss only for compactness
                fontSize = 11.sp,
                color = CalGrey,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                entry.level.name.first().toString(),
                fontSize = 11.sp,
                color = color,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                entry.tag,
                fontSize = 11.sp,
                color = color,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            entry.message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}
