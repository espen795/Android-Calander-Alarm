package io.github.calendaralarm.worker

import android.content.SharedPreferences
import io.github.calendaralarm.data.AppLogger

/**
 * P2A #6 (review #6): decision logic for the SYNC_FAILED notification as a PURE function
 * of (prefs, now) — decoupled from the worker instance so the counter is PERSISTENT.
 *
 * Problem this solves: the old `private var runAttemptCount` in AlarmSyncWorker was
 * per worker instance. WorkManager creates a NEW worker per run, so the counter was
 * always 0 and SYNC_FAILED was NEVER notified.
 *
 * Rules:
 * - [KEY_CONSECUTIVE_FAILURES] lives in device-protected prefs → survives worker
 *   instantiation, app restart and reboot (before unlock).
 * - Notify only after >= [MIN_FAILURES_BEFORE_NOTIFY] CONSECUTIVE failed runs.
 * - After that max 1x per [NOTIFY_DEDUP_WINDOW_MS] (12h) — existing dedup kept.
 * - [onSuccess] resets the counter (and clears the dedup marker).
 */
object SyncFailurePolicy {

    private const val TAG = "SyncFailurePolicy"

    const val PREFS_NAME = "alarm_sync_prefs"
    const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
    const val KEY_FAIL_NOTIFIED_AT = "sync_fail_notified_at"

    /** Notify only after this number of consecutive failed runs (first attempts stay silent — retry). */
    const val MIN_FAILURES_BEFORE_NOTIFY = 3

    /** Max 1 notification per 12 hours (existing dedup). */
    const val NOTIFY_DEDUP_WINDOW_MS = 12L * 3_600_000L

    data class Decision(
        val notify: Boolean,
        val newFailureCount: Int,
        val newNotifiedAt: Long
    )

    /**
     * Determines whether a failed run must produce a SYNC_FAILED notification.
     * Reads the counter from [prefs], adds +1, and checks the 12h dedup.
     * Saves NOTHING — the caller must call [record] (or prefs directly).
     */
    fun onFailure(prefs: SharedPreferences, now: Long, reason: String): Decision {
        val failures = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0) + 1
        if (failures < MIN_FAILURES_BEFORE_NOTIFY) {
            AppLogger.d(TAG, "SYNC_FAILED attempt $failures < $MIN_FAILURES_BEFORE_NOTIFY — silent (retry): $reason")
            return Decision(notify = false, newFailureCount = failures, newNotifiedAt = prefs.getLong(KEY_FAIL_NOTIFIED_AT, 0))
        }
        val lastNotified = prefs.getLong(KEY_FAIL_NOTIFIED_AT, 0)
        if (now - lastNotified < NOTIFY_DEDUP_WINDOW_MS) {
            AppLogger.d(TAG, "SYNC_FAILED already notified within 12h (failure #$failures) — silent: $reason")
            return Decision(notify = false, newFailureCount = failures, newNotifiedAt = lastNotified)
        }
        AppLogger.w(TAG, "SYNC_FAILED failure #$failures >= $MIN_FAILURES_BEFORE_NOTIFY and >12h since last notification — NOTIFY: $reason")
        return Decision(notify = true, newFailureCount = failures, newNotifiedAt = now)
    }

    /** Saves a [Decision] in prefs (counter + possibly notification time). */
    fun record(prefs: SharedPreferences, decision: Decision) {
        prefs.edit()
            .putInt(KEY_CONSECUTIVE_FAILURES, decision.newFailureCount)
            .putLong(KEY_FAIL_NOTIFIED_AT, decision.newNotifiedAt)
            .apply()
    }

    /**
     * Success: reset ONLY the consecutive-failure counter. KEY_FAIL_NOTIFIED_AT is
     * NOT cleared — the 12h dedup must also apply when failing/succeeding alternates (otherwise
     * a success directly followed by a new failure streak would immediately
     * notify again within the 12h window after 3 runs).
     */
    fun onSuccess(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_CONSECUTIVE_FAILURES)
            .apply()
    }
}
