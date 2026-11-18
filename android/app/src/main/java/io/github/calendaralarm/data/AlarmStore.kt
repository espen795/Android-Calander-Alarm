package io.github.calendaralarm.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent store for app-owned alarms.
 *
 * Alarm metadata (times/labels) is not sensitive, so a regular SharedPreferences
 * is used. The API token remains in EncryptedSharedPreferences (SettingsRepository).
 *
 * Supports per-date replacement so nightly smart-alarm commands replace tomorrow's
 * alarm instead of accumulating recurring alarms (fixes H-13).
 */
class AlarmStore(context: Context) {

    // FC-01/H-02 fix: Companion-level lock ensures thread-safety across ALL instances.
    // Previously @Synchronized was per-instance, allowing race conditions when
    // multiple AlarmStore instances existed (data loss on concurrent writes).
    private object StoreLock

    @Serializable
    data class OwnedAlarm(
        val id: Int,
        val triggerAtMillis: Long,
        val timeLabel: String,      // "HH:mm"
        val label: String,
        val date: String,           // "YYYY-MM-DD" target date
        val type: String,           // "office" | "home" | "off" | "manual" | "test"
        val enabled: Boolean = true,
        val isSnooze: Boolean = false,
        val originalTriggerMillis: Long = triggerAtMillis,
        val repeatDays: List<Int> = emptyList(),  // HN-05: 1=Mon..7=Sun, empty = one-shot
        val preAlarmId: Int = -1,   // F4: PendingIntent request code for pre-alarm, -1 = none
        val skipCount: Int = 0,     // F5: number of times to skip this repeat alarm
        val snoozeMinutesOverride: Int? = null,  // v2.5.0: per-alarm snooze override (null = use global, 0 = no snooze)
        // v2.6.13 H3: true when the USER disabled this alarm (via UI toggle).
        // Distinguishes user-disable vs auto-disable (e.g. handleAlarmFired sets enabled=false
        // as ringing-state — that is automatic and userDisabled stays false).
        // Only when userDisabled=true may the sync (replaceForDate) NOT re-enable the alarm.
        val userDisabled: Boolean = false
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    // FC-01/H-02 fix: All operations use synchronized(StoreLock) for cross-instance safety.
    // Writes use commit() (synchronous) instead of apply() (async) so a
    // process kill cannot lose a "scheduled" entry while AlarmManager retains the PI.

    fun save(alarm: OwnedAlarm) = synchronized(StoreLock) {
        val alarms = loadAll().toMutableMap()
        alarms[alarm.id] = alarm
        persist(alarms)
    }

    fun remove(id: Int): OwnedAlarm? = synchronized(StoreLock) {
        val alarms = loadAll().toMutableMap()
        val removed = alarms.remove(id)
        if (removed != null) persist(alarms)
        removed
    }

    fun get(id: Int): OwnedAlarm? = synchronized(StoreLock) { loadAll()[id] }

    fun getAll(): List<OwnedAlarm> = synchronized(StoreLock) { loadAll().values.sortedBy { it.triggerAtMillis } }

    fun getEnabled(): List<OwnedAlarm> = synchronized(StoreLock) { getAll().filter { it.enabled } }

    fun getNext(): OwnedAlarm? = synchronized(StoreLock) {
        getEnabled().filter { it.triggerAtMillis > System.currentTimeMillis() }
            .minByOrNull { it.triggerAtMillis }
    }

    fun findByDate(date: String): OwnedAlarm? = synchronized(StoreLock) {
        getAll().firstOrNull { it.date == date && !it.isSnooze }
    }

    fun removeAll(): List<OwnedAlarm> = synchronized(StoreLock) {
        val all = getAll()
        // HN-03: synchronous commit
        prefs.edit().remove(KEY_ALARMS).remove(KEY_NEXT_ID).commit()
        all
    }

    fun nextId(): Int = synchronized(StoreLock) {
        val current = prefs.getInt(KEY_NEXT_ID, 1)
        // HN-03: synchronous commit so ID is never reused after process death
        prefs.edit().putInt(KEY_NEXT_ID, current + 1).commit()
        current
    }

    /**
     * F5: Increment skip count for a repeat alarm (called when user taps "Skip this time").
     */
    fun incrementSkipCount(id: Int): OwnedAlarm? = synchronized(StoreLock) {
        val alarm = get(id) ?: return@synchronized null
        val updated = alarm.copy(skipCount = alarm.skipCount + 1)
        save(updated)
        updated
    }

    /**
     * F5: Decrement skip count (called when a skipped occurrence is consumed).
     * Returns the updated alarm, or null if alarm not found or skipCount already 0.
     */
    fun decrementSkipCount(id: Int): OwnedAlarm? = synchronized(StoreLock) {
        val alarm = get(id) ?: return@synchronized null
        if (alarm.skipCount <= 0) return@synchronized alarm
        val updated = alarm.copy(skipCount = alarm.skipCount - 1)
        save(updated)
        updated
    }

    /**
     * F5: Reset skip count to 0 (called when server pushes a new alarm for this date).
     */
    fun resetSkipCount(id: Int): OwnedAlarm? = synchronized(StoreLock) {
        val alarm = get(id) ?: return@synchronized null
        if (alarm.skipCount == 0) return@synchronized alarm
        val updated = alarm.copy(skipCount = 0)
        save(updated)
        updated
    }

    /**
     * F4: Update the pre-alarm PendingIntent request code for an alarm.
     */
    fun updatePreAlarmId(id: Int, preAlarmId: Int): OwnedAlarm? = synchronized(StoreLock) {
        val alarm = get(id) ?: return@synchronized null
        val updated = alarm.copy(preAlarmId = preAlarmId)
        save(updated)
        updated
    }

    /**
     * F1: Toggle enabled state of an alarm. Returns the updated alarm.
     */
    fun setEnabled(id: Int, enabled: Boolean): OwnedAlarm? = synchronized(StoreLock) {
        val alarm = get(id) ?: return@synchronized null
        val updated = alarm.copy(enabled = enabled)
        save(updated)
        updated
    }

    /**
     * v2.6.13 H3: Mark an alarm as disabled by the USER (UI toggle in MainScreen).
     * userDisabled=true means: the sync may NOT re-enable this alarm
     * (see AlarmRepository.replaceForDate). When the user re-enables it,
     * the flag is cleared again.
     *
     * Auto-disable (e.g. handleAlarmFired sets enabled=false as ringing-state) does
     * NOT touch this flag — that path uses store.save(alarm.copy(enabled=false)) directly.
     */
    fun setUserDisabled(id: Int, userDisabled: Boolean): OwnedAlarm? = synchronized(StoreLock) {
        val alarm = get(id) ?: return@synchronized null
        val updated = alarm.copy(enabled = !userDisabled, userDisabled = userDisabled)
        save(updated)
        updated
    }

    private fun loadAll(): Map<Int, OwnedAlarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<Int, OwnedAlarm>>(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun persist(alarms: Map<Int, OwnedAlarm>) {
        // HN-03: commit() instead of apply() — crash-safe
        prefs.edit().putString(KEY_ALARMS, json.encodeToString(alarms)).commit()
    }

    companion object {
        private const val PREFS_FILE = "calendar_alarm_store"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_NEXT_ID = "next_id"
    }
}
