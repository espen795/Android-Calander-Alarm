package io.github.calendaralarm.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.calendaralarm.MainActivity
import io.github.calendaralarm.R
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.WidgetCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen widget v2.4.0 — Widget Cache Pattern + Native TextClock + Observability.
 *
 * IMPORTANT CHANGES v2.4.0:
 * - Native TextClock for the clock (ticks on its own in the launcher, no manual updates)
 * - Extensive logging via AppLogger for debugging on Samsung One UI
 * - All RemoteViews operations in try/catch with error logging
 * - previewLayout added to widget metadata (crucial for Samsung One UI 6+ grid)
 *
 * CRITICAL: This class is kept EXTREMELY simple.
 * - Reads ONLY WidgetCache (simple SharedPreferences strings)
 * - NO kotlinx.serialization
 * - NO complex JSON parsing
 * - NO AlarmRepository
 * - NO data classes
 *
 * Why: v2.2.6-v2.2.8 crashed due to complex logic in BroadcastReceiver.
 * v2.2.5 worked because it was simple.
 * v2.3.0 combines: simple + dynamic data (via WidgetCache).
 *
 * Update flow:
 * AlarmRepository.schedule() → WidgetCache.updateNextAlarm() → AlarmWidgetProvider.updateAllWidgets()
 * MainActivity.onResume() → WidgetCache.updateNextAlarm() → updateAllWidgets()
 */
class AlarmWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "AlarmWidgetProvider"
        const val ACTION_UPDATE_WIDGET = "io.github.calendaralarm.UPDATE_WIDGET"

        /**
         * Force an update of all widget instances.
         * Called from AlarmRepository, MainActivity, BootReceiver.
         */
        fun updateAllWidgets(context: Context) {
            try {
                AppLogger.i(TAG, "updateAllWidgets() called")
                val intent = Intent(context, AlarmWidgetProvider::class.java).apply {
                    action = ACTION_UPDATE_WIDGET
                    setPackage(context.packageName) // Android 14+ vereiste
                }
                context.sendBroadcast(intent)
                AppLogger.d(TAG, "updateAllWidgets() broadcast sent successfully")
            } catch (e: Exception) {
                AppLogger.e(TAG, "updateAllWidgets() failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        AppLogger.i(TAG, "onUpdate() called with ${appWidgetIds.size} widget(s): ${appWidgetIds.joinToString()}")
        try {
            // Simple loop — no complex logic
            appWidgetIds.forEach { appWidgetId ->
                try {
                    updateWidget(context, appWidgetManager, appWidgetId)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to update widget $appWidgetId: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            AppLogger.i(TAG, "onUpdate() completed successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "onUpdate() failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.d(TAG, "onReceive() action=${intent.action}")
        try {
            super.onReceive(context, intent)
            if (intent.action == ACTION_UPDATE_WIDGET) {
                AppLogger.i(TAG, "ACTION_UPDATE_WIDGET received, refreshing all widgets")
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(
                    ComponentName(context, AlarmWidgetProvider::class.java)
                )
                AppLogger.d(TAG, "Found ${ids.size} widget instance(s) to update")
                onUpdate(context, appWidgetManager, ids)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "onReceive() failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        AppLogger.d(TAG, "updateWidget() starting for appWidgetId=$appWidgetId")
        try {
            // Simpele RemoteViews — exact zoals v2.2.5 (die werkte)
            val views = RemoteViews(context.packageName, R.layout.widget_alarm)
            AppLogger.d(TAG, "RemoteViews created for appWidgetId=$appWidgetId")

            // TextClock (widget_time) is NO LONGER set manually — it ticks on its own
            // in the launcher via format24Hour="HH:mm"

            // Date — always works
            val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date())
            views.setTextViewText(R.id.widget_date, date)
            AppLogger.d(TAG, "Date set to '$date' for appWidgetId=$appWidgetId")

            // Alarm — from WidgetCache (simple string, never crashes)
            val alarmText = WidgetCache.getNextAlarmText(context)
            views.setTextViewText(R.id.widget_next_alarm, alarmText)
            AppLogger.d(TAG, "Alarm text set to '$alarmText' for appWidgetId=$appWidgetId")

            // Click → open app (worked in v2.2.5)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            AppLogger.d(TAG, "Click PendingIntent set for appWidgetId=$appWidgetId")

            appWidgetManager.updateAppWidget(appWidgetId, views)
            AppLogger.i(TAG, "Widget $appWidgetId updated successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "updateWidget() failed for appWidgetId=$appWidgetId: ${e.message ?: e.javaClass.simpleName}")
            // Try to render a minimal fallback widget
            try {
                val fallbackViews = RemoteViews(context.packageName, R.layout.widget_alarm)
                fallbackViews.setTextViewText(R.id.widget_date, "")
                fallbackViews.setTextViewText(R.id.widget_next_alarm, "Calendar Alarm")
                appWidgetManager.updateAppWidget(appWidgetId, fallbackViews)
                AppLogger.w(TAG, "Fallback widget rendered for appWidgetId=$appWidgetId")
            } catch (fallbackError: Exception) {
                AppLogger.e(TAG, "Fallback widget also failed for appWidgetId=$appWidgetId: ${fallbackError.message ?: fallbackError.javaClass.simpleName}")
            }
        }
    }
}
