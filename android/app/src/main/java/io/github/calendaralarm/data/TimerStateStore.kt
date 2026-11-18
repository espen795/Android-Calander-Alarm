package io.github.calendaralarm.data

import android.content.Context
import android.content.SharedPreferences

private const val TAG = "TimerStateStore"

/**
 * v2.5.6 Story 1: Persistent storage for timer state.
 *
 * Saves the timer state in SharedPreferences so the timer survives:
 * - App kill (swipe away, OOM killer)
 * - Device reboot
 * - Process death
 *
 * Uses SharedPreferences (not DataStore) because:
 * - Simple key-value pairs, no async complexity
 * - commit() is synchronous — crash-safe (just like AlarmStore)
 * - Directly available in BroadcastReceivers (BootReceiver, TimerFireReceiver)
 *
 * Important: endTimeWallClock is in WALL CLOCK time (System.currentTimeMillis),
 * not elapsedRealtime — wall clock survives reboot, elapsedRealtime resets to 0.
 */
class TimerStateStore(context: Context) {

    /**
     * Immutable snapshot of the timer state.
     *
     * @param isRunning true when the timer is actively running
     * @param endTimeWallClock Wall clock timestamp (System.currentTimeMillis) when the timer expires
     * @param totalMs Total duration in milliseconds
     * @param remainingMsAtSave Remaining time at the moment of saving (for paused timers)
     * @param customHours Custom hours input (for UI restore)
     * @param customMinutes Custom minutes input
     * @param customSeconds Custom seconds input
     */
    data class TimerState(
        val isRunning: Boolean,
        val endTimeWallClock: Long,      // System.currentTimeMillis() based
        val totalMs: Long,
        val remainingMsAtSave: Long,     // For paused: how much was left
        val customHours: Int,
        val customMinutes: Int,
        val customSeconds: Int
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // v2.5.6: Cross-instance thread safety (just like AlarmStore)
    private object StoreLock

    /**
     * Save the timer state. Use commit() (synchronous) for crash-safety.
     */
    fun save(state: TimerState) = synchronized(StoreLock) {
        prefs.edit()
            .putBoolean(KEY_IS_RUNNING, state.isRunning)
            .putLong(KEY_END_TIME_WALL_CLOCK, state.endTimeWallClock)
            .putLong(KEY_TOTAL_MS, state.totalMs)
            .putLong(KEY_REMAINING_MS_AT_SAVE, state.remainingMsAtSave)
            .putInt(KEY_CUSTOM_HOURS, state.customHours)
            .putInt(KEY_CUSTOM_MINUTES, state.customMinutes)
            .putInt(KEY_CUSTOM_SECONDS, state.customSeconds)
            .commit()
        AppLogger.i(TAG, "TimerState saved: isRunning=${state.isRunning}, endTime=${state.endTimeWallClock}, remaining=${state.remainingMsAtSave}")
    }

    /**
     * Read the saved timer state. Returns null when no timer is saved.
     */
    fun load(): TimerState? = synchronized(StoreLock) {
        if (!prefs.contains(KEY_IS_RUNNING)) {
            return@synchronized null
        }
        TimerState(
            isRunning = prefs.getBoolean(KEY_IS_RUNNING, false),
            endTimeWallClock = prefs.getLong(KEY_END_TIME_WALL_CLOCK, 0L),
            totalMs = prefs.getLong(KEY_TOTAL_MS, 0L),
            remainingMsAtSave = prefs.getLong(KEY_REMAINING_MS_AT_SAVE, 0L),
            customHours = prefs.getInt(KEY_CUSTOM_HOURS, 0),
            customMinutes = prefs.getInt(KEY_CUSTOM_MINUTES, 25),
            customSeconds = prefs.getInt(KEY_CUSTOM_SECONDS, 0)
        )
    }

    /**
     * Clear the timer state (on stop/reset/expired).
     */
    fun clear() = synchronized(StoreLock) {
        prefs.edit()
            .remove(KEY_IS_RUNNING)
            .remove(KEY_END_TIME_WALL_CLOCK)
            .remove(KEY_TOTAL_MS)
            .remove(KEY_REMAINING_MS_AT_SAVE)
            .remove(KEY_CUSTOM_HOURS)
            .remove(KEY_CUSTOM_MINUTES)
            .remove(KEY_CUSTOM_SECONDS)
            .commit()
        AppLogger.i(TAG, "TimerState cleared")
    }

    /**
     * Check whether an active timer is saved (isRunning=true).
     */
    fun hasActiveTimer(): Boolean = synchronized(StoreLock) {
        prefs.getBoolean(KEY_IS_RUNNING, false)
    }

    /**
     * Compute the remaining time based on endTimeWallClock.
     * Returns null when there is no state, or when the timer has already expired (<= 0).
     * For paused timers: return remainingMsAtSave.
     */
    fun getRemainingMs(): Long? = synchronized(StoreLock) {
        val state = load() ?: return@synchronized null
        if (!state.isRunning) {
            // Paused: return the saved remaining time
            return@synchronized state.remainingMsAtSave
        }
        // Running: compute based on wall clock
        val remaining = state.endTimeWallClock - System.currentTimeMillis()
        return@synchronized remaining
    }

    companion object {
        private const val PREFS_FILE = "calalarm_timer_state"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_END_TIME_WALL_CLOCK = "end_time_wall_clock"
        private const val KEY_TOTAL_MS = "total_ms"
        private const val KEY_REMAINING_MS_AT_SAVE = "remaining_ms_at_save"
        private const val KEY_CUSTOM_HOURS = "custom_hours"
        private const val KEY_CUSTOM_MINUTES = "custom_minutes"
        private const val KEY_CUSTOM_SECONDS = "custom_seconds"
    }
}
