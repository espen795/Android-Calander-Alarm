package io.github.calendaralarm.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
// AppLogger is in the same package (io.github.calendaralarm.data) — no import needed
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
        // v2.6.13 H3: true if the USER disabled this alarm (via UI toggle).
        // Distinguishes user-disable vs auto-disable (e.g. handleAlarmFired sets enabled=false
        // as ringing-state — that is auto and userDisabled stays false).
        // Only with userDisabled=true may the sync (replaceForDate) NOT re-enable the alarm.
        val userDisabled: Boolean = false
    )

    // P1 review#1: device-protected storage — readable before the first unlock
    // after a reboot (Direct Boot), so an alarm can ring before unlock.
    private val prefs: SharedPreferences =
        DeviceProtectedStorage.prefs(context, PREFS_FILE)

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
        getEnabled().filter { it.triggerAtMillis > io.github.calendaralarm.data.AppClock.now() }
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
     * F5: Increment skip count for a repeat alarm (called when the user taps "Skip this time").
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
     * (see AlarmRepository.replaceForDate). When re-enabled by the user,
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

    private var corruptNotified = false
    private var lastQuarantinedRaw: String? = null

    /**
     * P1 fix review#2 + B3 (review): decode-fout afvangen zonder data stil te verliezen.
     *
     * - The raw (corrupt) blob is quarantined ONCE ([KEY_ALARMS_QUARANTINE],
     *   plus max. [MAX_QUARANTINE_COPIES] timestamp copies) — after that persist() may
     *   simply overwrite the main key: the raw data is secured and a permanent
     *   write block would break the alarm chain (AlarmManager set, store not).
     * - An ERROR is logged and (once per process) a notification is triggered
     *   via [onCorruption].
     * - If a valid backup ([KEY_ALARMS_BACKUP]) exists, it is used as the source on
     *   corruption of the main key (last-known-good copy).
     */
    private fun loadAll(): Map<Int, OwnedAlarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<Int, OwnedAlarm>>(raw)
        } catch (e: Exception) {
            AppLogger.e(TAG, "P1 review#2/B3: AlarmStore JSON corrupt — quarantine + backup check: ${e.message}")
            quarantineCorrupt(raw)
            loadBackup() ?: run {
                notifyCorruptionOnce()
                emptyMap()
            }
        }
    }

    private fun quarantineCorrupt(raw: String) {
        // B3: only write quarantine if this raw blob is not yet secured
        // (same content as last time → skip; otherwise keep max 3 timestamp copies)
        if (lastQuarantinedRaw == raw &&
            prefs.getString(KEY_ALARMS_QUARANTINE, null) == raw) return
        lastQuarantinedRaw = raw
        try {
            val editor = prefs.edit().putString(KEY_ALARMS_QUARANTINE, raw)
            val ts = io.github.calendaralarm.data.AppClock.now()
            editor.putString("$KEY_ALARMS_CORRUPT_PREFIX$ts", raw)
            // trim history to max 3 copies
            val tsKeys = prefs.all.keys
                .filter { it.startsWith(KEY_ALARMS_CORRUPT_PREFIX) }
                .sorted()
            if (tsKeys.size >= MAX_QUARANTINE_COPIES) {
                tsKeys.take(tsKeys.size - MAX_QUARANTINE_COPIES + 1).forEach { editor.remove(it) }
            }
            editor.commit()
        } catch (e: Exception) {
            AppLogger.e(TAG, "P1: quarantine write failed: ${e.message}")
        }
    }

    private fun loadBackup(): Map<Int, OwnedAlarm>? {
        val raw = prefs.getString(KEY_ALARMS_BACKUP, null) ?: return null
        return try {
            val decoded = json.decodeFromString<Map<Int, OwnedAlarm>>(raw)
            AppLogger.w(TAG, "P1 review#2: main key corrupt — falling back to alarms_backup (${decoded.size} entries)")
            decoded
        } catch (e: Exception) {
            AppLogger.e(TAG, "P1 review#2: backup also corrupt: ${e.message}")
            null
        }
    }

    private fun notifyCorruptionOnce() {
        if (corruptNotified) return
        corruptNotified = true
        try {
            onCorruption?.invoke()
        } catch (e: Exception) {
            AppLogger.e(TAG, "P1: onCorruption callback crashed: ${e.message}")
        }
    }

    /**
     * B3 (review): persist now returns a Boolean (true = written) and logs ERROR
     * on failure, so the caller (e.g. schedule) can detect that the store did NOT
     * get the entry while AlarmManager did.
     */
    private fun persist(alarms: Map<Int, OwnedAlarm>): Boolean {
        // HN-03: commit() instead of apply() — crash-safe
        val encoded = json.encodeToString(alarms)
        // B3: no permanent write block anymore. The corrupt blob is already quarantined
        // on the first loadAll (see quarantineCorrupt) — after that the main key may
        // simply be overwritten, otherwise all saves would remain permanently refused.
        val editor = prefs.edit()
        editor.putString(KEY_ALARMS, encoded)
        editor.putString(KEY_ALARMS_BACKUP, encoded)  // last-known-good copy
        val ok = editor.commit()
        if (!ok) {
            AppLogger.e(TAG, "B3: persist() commit() failed — store NOT written (AlarmManager entry may now be out of sync)")
        }
        return ok
    }

    companion object {
        private const val TAG = "AlarmStore"
        private const val PREFS_FILE = "calendar_alarm_store"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_NEXT_ID = "next_id"
        const val KEY_ALARMS_BACKUP = "alarms_backup"
        const val KEY_ALARMS_QUARANTINE = "alarms_quarantine"
        const val KEY_ALARMS_CORRUPT_PREFIX = "alarms_corrupt_"
        /** B3: max number of timestamp quarantine copies (history) — prevents unbounded growth. */
        const val MAX_QUARANTINE_COPIES = 3

        /**
         * P1 review#2: callback on detected corruption (once per process).
         * Set from CalendarAlarmApp → shows "Alarm store corrupted — open the app".
         * Null = no notification (e.g. in tests).
         */
        @Volatile
        var onCorruption: (() -> Unit)? = null
    }
}
