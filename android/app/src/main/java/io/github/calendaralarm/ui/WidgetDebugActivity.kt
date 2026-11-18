package io.github.calendaralarm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.WidgetCache
import io.github.calendaralarm.receiver.AlarmWidgetProvider
import io.github.calendaralarm.ui.theme.CalendarAlarmTheme

/**
 * v2.3.0 — Widget Debug Activity.
 *
 * Test de widget ZONDER elke keer een nieuwe APK te bouwen:
 * - Toont huidige widget cache inhoud
 * - Forceert widget refresh
 * - Toont recente AppLogger entries (widget logs)
 * - Test alarm formatting
 *
 * Bereikbaar via: In-app setting (of via ADB):
 *   adb shell am start -n io.github.calendaralarm/.ui.WidgetDebugActivity
 */
class WidgetDebugActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CalendarAlarmTheme(darkTheme = true) {
                WidgetDebugScreen(
                    onForceRefresh = { forceWidgetRefresh() },
                    onShowCache = { getWidgetCacheInfo() },
                    onGetRecentLogs = { getRecentWidgetLogs() }
                )
            }
        }
    }

    private fun forceWidgetRefresh() {
        try {
            val repo = AlarmRepository(this)
            repo.refreshWidget()
            AppLogger.i("WidgetDebug", "Force refresh triggered")
        } catch (e: Exception) {
            AppLogger.e("WidgetDebug", "Force refresh failed: ${e.message}")
        }
    }

    private fun getWidgetCacheInfo(): String {
        return try {
            val nextAlarm = WidgetCache.getNextAlarmText(this)

            val repo = AlarmRepository(this)
            val store = repo.store
            val allAlarms = store.getAll()
            val nextAlarmObj = store.getNext()

            buildString {
                appendLine("=== Widget Cache Info ===")
                appendLine("Cache text: '$nextAlarm'")
                appendLine()
                appendLine("=== Store Info ===")
                appendLine("Total alarms: ${allAlarms.size}")
                appendLine("Enabled alarms: ${store.getEnabled().size}")
                appendLine("Next alarm object: ${nextAlarmObj?.let { "${it.timeLabel} — ${it.label} (${it.date})" } ?: "null"}")
                appendLine()
                appendLine("=== All Alarms ===")
                allAlarms.forEach { alarm ->
                    appendLine("  [${if (alarm.enabled) "ON" else "OFF"}] ${alarm.timeLabel} ${alarm.date} — ${alarm.label} (${alarm.type})")
                }
            }
        } catch (e: Exception) {
            "Error reading cache: ${e.message}"
        }
    }

    private fun getRecentWidgetLogs(): List<String> {
        return AppLogger.getAll()
            .filter { it.tag.contains("Widget", ignoreCase = true) || it.tag.contains("AlarmRepository", ignoreCase = true) }
            .takeLast(20)
            .map { it.toDisplayString() }
    }
}

@Composable
fun WidgetDebugScreen(
    onForceRefresh: () -> Unit,
    onShowCache: () -> String,
    onGetRecentLogs: () -> List<String>
) {
    var cacheInfo by remember { mutableStateOf("Tap 'Show Cache' to load") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "🔧 Widget Debug",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "v2.3.0 — Samsung One UI Widget Test",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Action buttons
        Button(
            onClick = {
                onForceRefresh()
                cacheInfo = "Refresh triggered! Check the widget on the home screen."
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔄 Force Widget Refresh")
        }

        OutlinedButton(
            onClick = { cacheInfo = onShowCache() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📋 Show Cache Info")
        }

        OutlinedButton(
            onClick = { logs = onGetRecentLogs() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📜 Show Widget Logs")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Cache info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = cacheInfo,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }

        // Logs card
        if (logs.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Widget Logs (last 20):",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    logs.forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = when {
                                log.contains("ERROR") -> MaterialTheme.colorScheme.error
                                log.contains("WARN") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "📱 Test Instructions",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = """
                        1. Add widget to home screen (long press → Widgets → Calendar Alarm)
                        2. Open this debug activity
                        3. Tap "Force Widget Refresh"
                        4. Check whether the widget shows time/date/alarm
                        5. Check the logs above for errors

                        ADB commands:
                        • Widget logs: adb logcat -s AlarmWidgetProvider:* WidgetCache:*
                        • Force update: adb shell am broadcast -a io.github.calendaralarm.UPDATE_WIDGET -n io.github.calendaralarm/.receiver.AlarmWidgetProvider
                        • Open debug: adb shell am start -n io.github.calendaralarm/.ui.WidgetDebugActivity
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
