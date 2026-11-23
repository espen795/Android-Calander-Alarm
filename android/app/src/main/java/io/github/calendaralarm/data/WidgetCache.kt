package io.github.calendaralarm.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Widget Cache — stores pre-formatted strings for the home screen widget.
 *
 * v2.3.0 Widget Cache Pattern:
 * - Widget reads ONLY simple strings from SharedPreferences
 * - NO kotlinx.serialization in widget (R8 strips serializers in release)
 * - NO complex JSON parsing in BroadcastReceiver
 * - App updates cache on every alarm change
 *
 * Why this works:
 * - SharedPreferences.getString() is 100% reliable in BroadcastReceiver
 * - No dependencies, no reflection, no obfuscation issues
 * - Samsung One UI renders this without problems
 */
object WidgetCache {

    private const val PREFS_FILE = "calalarm_widget_cache"
    private const val KEY_NEXT_ALARM_TEXT = "next_alarm_text"
    private const val KEY_LAST_UPDATE = "last_update"

    private fun prefs(context: Context): SharedPreferences =
        DeviceProtectedStorage.prefs(context, PREFS_FILE)  // P1 #1: DP storage (widget runs before unlock)

    /**
     * Update the cache with the text for the next upcoming alarm.
     * Called from AlarmRepository on every schedule/cancel.
     *
     * v2.5.3 PM fix C-02: use formatNextAlarm() (with enabled/past checks)
     * instead of the old formatAlarmText() — one consistent function.
     *
     * @param context Application context
     * @param alarm The next upcoming alarm, or null if there is none
     */
    fun updateNextAlarm(context: Context, alarm: AlarmStore.OwnedAlarm?) {
        try {
            val text = formatNextAlarm(alarm)
            prefs(context).edit()
                .putString(KEY_NEXT_ALARM_TEXT, text)
                .putLong(KEY_LAST_UPDATE, io.github.calendaralarm.data.AppClock.now())
                .apply()
        } catch (e: Exception) {
            AppLogger.e("WidgetCache", "Failed to update cache: ${e.message}")
        }
    }

    /**
     * Update the cache with a raw string (for compatibility).
     */
    fun updateNextAlarm(context: Context, text: String) {
        try {
            prefs(context).edit()
                .putString(KEY_NEXT_ALARM_TEXT, text)
                .putLong(KEY_LAST_UPDATE, io.github.calendaralarm.data.AppClock.now())
                .apply()
        } catch (e: Exception) {
            AppLogger.e("WidgetCache", "Failed to update cache: ${e.message}")
        }
    }

    /**
     * v2.5.3: Public formatNextAlarm() for unit tests.
     * Format: "{emoji} {day} {time} — {label}"
     * - "No alarm scheduled" when alarm is null/disabled/in the past
     * - "today" for today, "tomorrow" for tomorrow, weekday (2 letters) for later
     * - Emoji: office=🏢, home=🏠, off=😴, test=🧪, else=⏰
     */
    fun formatNextAlarm(alarm: AlarmStore.OwnedAlarm?): String {
        if (alarm == null) return "No alarm scheduled"
        if (!alarm.enabled) return "No alarm scheduled"
        if (alarm.triggerAtMillis <= io.github.calendaralarm.data.AppClock.now()) return "No alarm scheduled"

        val emoji = when (alarm.type) {
            "office" -> "🏢"
            "home" -> "🏠"
            "off" -> "😴"
            "test" -> "🧪"
            else -> "⏰"
        }

        // Determine day display: "today", "tomorrow", or 2-letter weekday
        val now = Calendar.getInstance().apply { timeInMillis = io.github.calendaralarm.data.AppClock.now() }
        val alarmCal = Calendar.getInstance().apply { timeInMillis = alarm.triggerAtMillis }

        // Check if alarm is tomorrow
        val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isTomorrow = isSameDay(tomorrow, alarmCal)

        val dayStr = when {
            isSameDay(now, alarmCal) -> "today"
            isTomorrow -> "tomorrow"
            else -> {
                // Weekday in 2 letters (nl locale): ma, di, wo, do, vr, za, zo
                val dayFormat = SimpleDateFormat("EE", Locale.getDefault())
                dayFormat.format(Date(alarm.triggerAtMillis)).take(2)
            }
        }

        return "$emoji $dayStr ${alarm.timeLabel} — ${alarm.label}"
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * v2.5.3 PM fix C-02: formatAlarmText() removed — was inconsistent with formatNextAlarm().
     * Widget now uses formatNextAlarm() via updateNextAlarm().
     * Emoji for "off" is now consistent 😴 (was 🏖️ in the old version).
     */

    /**
     * Read the cached alarm text for the widget.
     * Always a string — never null (fallback: "Calendar Alarm").
     */
    fun getNextAlarmText(context: Context): String {
        return try {
            prefs(context).getString(KEY_NEXT_ALARM_TEXT, null) ?: "Calendar Alarm"
        } catch (e: Exception) {
            "Calendar Alarm"
        }
    }

    /**
     * Check when the cache was last updated.
     */
    fun getLastUpdate(context: Context): Long {
        return try {
            prefs(context).getLong(KEY_LAST_UPDATE, 0L)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Clear the cache (for tests).
     */
    fun clear(context: Context) {
        try {
            prefs(context).edit().clear().apply()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
