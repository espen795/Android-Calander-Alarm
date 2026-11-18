package io.github.calendaralarm.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-app ring buffer logger.
 *
 * Keeps the last N log entries in memory for in-app display and export.
 * Also forwards to Android Log (stripped in release builds via ProGuard).
 *
 * SEC-13: No tokens, no personal data (calendar subjects) in logs.
 * Only sanitized operational messages.
 */
object AppLogger {

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        enum class Level { DEBUG, INFO, WARN, ERROR }

        fun toDisplayString(): String = "$timestamp [${level.name}] $tag: $message"
        fun toExportString(): String = "$timestamp,${level.name},$tag,$message"
    }

    private const val MAX_ENTRIES = 500
    private val entries = ConcurrentLinkedQueue<LogEntry>()
    private val _entriesFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val entriesFlow = _entriesFlow.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun add(level: LogEntry.Level, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = message.take(500) // cap message length
        )
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
        _entriesFlow.value = entries.toList()

        // Also forward to Android Log (stripped in release)
        when (level) {
            LogEntry.Level.DEBUG -> Log.d(tag, message)
            LogEntry.Level.INFO -> Log.i(tag, message)
            LogEntry.Level.WARN -> Log.w(tag, message)
            LogEntry.Level.ERROR -> Log.e(tag, message)
        }
    }

    fun d(tag: String, message: String) = add(LogEntry.Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = add(LogEntry.Level.INFO, tag, message)
    fun w(tag: String, message: String) = add(LogEntry.Level.WARN, tag, message)
    fun e(tag: String, message: String) = add(LogEntry.Level.ERROR, tag, message)

    /**
     * Log app lifecycle events.
     */
    fun lifecycle(event: String) = i("Lifecycle", event)

    /**
     * Export logs as CSV text.
     */
    fun exportToCsv(): String {
        val sb = StringBuilder()
        sb.append("timestamp,level,tag,message\n")
        entries.forEach { entry ->
            // Escape quotes and newlines in message
            val msg = entry.message.replace("\"", "\"\"").replace("\n", " ")
            sb.append("\"${entry.timestamp}\",${entry.level.name},\"${entry.tag}\",\"$msg\"\n")
        }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _entriesFlow.value = emptyList()
    }

    fun getAll(): List<LogEntry> = entries.toList()
}
