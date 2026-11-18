package io.github.calendaralarm.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.service.AlarmNotificationHelper
import io.github.calendaralarm.sync.SyncRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Periodic worker that polls the Calendar Alarm server for tomorrow's alarm plan
 * and schedules the alarm locally.
 *
 * Pull model: the app initiates the HTTPS connection (no inbound server needed).
 * Runs every evening at ~21:00 (scheduled via WorkManager with a periodic request
 * + an immediate one-shot on app start / boot).
 */
class AlarmSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AlarmSyncWorker"
        const val WORK_NAME = "calendar_alarm_sync"
        private const val PREFS_NAME = "alarm_sync_prefs"
        private const val KEY_LAST_WAKE_TIME = "last_wake_time"
        private const val KEY_LAST_WAKE_DATE = "last_wake_date"
        // v2.6.13 L6: dedup for SYNC_FAILED — max 1 notification per 12 hours, only after 2 attempts
        private const val KEY_SYNC_FAIL_NOTIFIED_AT = "sync_fail_notified_at"
        private const val SYNC_FAIL_DEDUP_WINDOW_MS = 12L * 3_600_000L
    }

    /**
     * v2.6.13 L6: SYNC_FAILED notification with dedup — the first 2 attempts stay silent
     * (retry with backoff), after that max 1× per 12 hours. Reset on success (see bottom of doWork).
     */
    private var runAttemptCount = 0

    private fun notifySyncFailedDeduped(reason: String) {
        runAttemptCount++
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (runAttemptCount < 2) {
            AppLogger.d(TAG, "SYNC_FAILED attempt $runAttemptCount < 2 — silent (retry)")
            return
        }
        if (now - prefs.getLong(KEY_SYNC_FAIL_NOTIFIED_AT, 0) < SYNC_FAIL_DEDUP_WINDOW_MS) {
            AppLogger.d(TAG, "SYNC_FAILED already reported within 12h — silent")
            return
        }
        prefs.edit().putLong(KEY_SYNC_FAIL_NOTIFIED_AT, now).apply()
        AlarmNotificationHelper.notifyAlarmEvent(
            applicationContext,
            AlarmNotificationHelper.AlarmEvent.SYNC_FAILED,
            label = reason
        )
    }

    override suspend fun doWork(): Result {
        AppLogger.i(TAG, "Starting alarm sync (pull from server)")

        // v2.5.8: Alarm Freeze blocks AUTOMATED sync only
        // User-initiated actions in the UI are always allowed
        // Skip automated sync during freeze hours — try again next run
        val settings = io.github.calendaralarm.CalendarAlarmApp.settingsRepository
        if (settings.isWekkerFrozen()) {
            AppLogger.i(TAG, "Automated sync skipped: ${settings.getWekkerFreezeMessage()}")
            return Result.success() // Skip silently — try again next hour
        }

        val syncRepo = SyncRepository(applicationContext)
        val alarmRepo = AlarmRepository(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return try {
            // Check exact alarm permission first
            if (!alarmRepo.canScheduleExactAlarms()) {
                Log.w(TAG, "Exact alarm permission not granted — cannot schedule")
                // v2.6.13 L6: deduped (first 2 attempts silent, then max 1×/12h)
                notifySyncFailedDeduped("Grant exact alarm permission in the app settings")
                return Result.failure()
            }

            // Fetch tomorrow's plan from server
            val syncResult = syncRepo.fetchAlarmPlan()

            if (!syncResult.success || syncResult.plan == null) {
                Log.e(TAG, "Sync failed: ${syncResult.error}")
                notifySyncFailedDeduped(syncResult.error ?: "Unknown error")
                return Result.retry()
            }

            val plan = syncResult.plan

            // Parse the date from the plan (YYYY-MM-DD)
            val targetDate = try {
                LocalDate.parse(plan.date, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                // Fallback: tomorrow
                LocalDate.now().plusDays(1)
            }

            // No alarm (e.g. weekend without weekend rule) → cancel existing alarms for that day
            if (plan.wakeTime == null) {
                AppLogger.i(TAG, "No alarm for ${plan.date} (${plan.label}) — cancelling existing")
                // v2.6.12: diff before apply — only notify when something was actually removed
                val before = alarmRepo.store.getAll().filter { it.date == targetDate.toString() }
                // v2.6.13 L4: force=true → per-alarm REMOVED is silent; worker itself reports 1× aggregate
                val cancelled = alarmRepo.cancelForDate(targetDate, force = true)
                if (cancelled > 0) {
                    // E3: really removed → one notification
                    AlarmNotificationHelper.notifyAlarmEvent(
                        applicationContext,
                        AlarmNotificationHelper.AlarmEvent.REMOVED,
                        time = "😴",
                        label = "No alarm — ${plan.label}"
                    )
                } else {
                    // v2.6.12: nothing was there → silent (log only)
                    AppLogger.i(TAG, "No alarms existed for ${plan.date} — silent (no notification)")
                }
                AppLogger.i(TAG, "Cancelled $cancelled alarm(s) for ${plan.date} (before=${before.size})")
                return Result.success()
            }

            // v2.6.13 L1: compute the event BEFORE apply — the worker is the ONLY notifier.
            // SET = there was no enabled smart alarm yet for this date;
            // CHANGED = time or label differed; null = identical → silent.
            // v2.6.13 H3: a user-disabled alarm (userDisabled=true) does NOT count as
            // "already exists" — the sync may not re-enable the user's choice or report it as a change
            // to report. If ALL existing alarms are user-disabled → event=null (silent),
            // because replaceForDate leaves them disabled (see H3 guard there).
            val dateStr = targetDate.toString()
            val existingForDate = alarmRepo.store.getAll().filter { it.date == dateStr && it.type != "manual" && it.type != "test" }
            val activeForDate = existingForDate.filter { !it.userDisabled }
            val event = when {
                existingForDate.isNotEmpty() && activeForDate.isEmpty() && existingForDate.all { it.userDisabled } ->
                    null  // v2.6.13 H3: all user-disabled → sync leaves it alone, silent
                activeForDate.none { it.enabled } -> AlarmNotificationHelper.AlarmEvent.SET
                activeForDate.any { it.timeLabel != plan.wakeTime || it.label != plan.label } ->
                    AlarmNotificationHelper.AlarmEvent.CHANGED
                else -> null   // identical → silent ("set to 10, still at 10")
            }

            // Schedule the alarm (replace any existing smart alarm for this date).
            // v2.6.13 L1: silent=true → replaceForDate notifies nothing (idempotent, ID reuse);
            // the worker below notifies ONLY when event != null.
            val scheduled = alarmRepo.replaceForDate(
                date = targetDate,
                timeLabel = plan.wakeTime,
                label = plan.label,
                type = plan.workLocation,
                silent = true
            )

            if (scheduled != null) {
                // v2.6.13 L6: success → reset the sync-fail dedup counter
                prefs.edit().remove(KEY_SYNC_FAIL_NOTIFIED_AT).apply()
                runAttemptCount = 0

                prefs.edit()
                    .putString(KEY_LAST_WAKE_TIME, plan.wakeTime)
                    .putString(KEY_LAST_WAKE_DATE, plan.date)
                    .apply()
                // v2.6.13 L1: worker is the ONLY notifier — 1 notification on SET/CHANGED, silent when identical
                event?.let {
                    AppLogger.i(TAG, "Alarm event $it for $dateStr: -> ${plan.wakeTime} (${plan.label})")
                    AlarmNotificationHelper.notifyAlarmEvent(
                        applicationContext,
                        it,
                        time = plan.wakeTime,
                        label = plan.label,
                        alarmId = scheduled.id
                    )
                    AppLogger.i(TAG, "Sync complete: event=$it — 1 notification")
                } ?: AppLogger.i(TAG, "Sync complete: identical plan — silent (no notification)")

                Result.success()
            } else {
                AppLogger.e(TAG, "Failed to schedule alarm from plan")
                notifySyncFailedDeduped("Could not schedule alarm")
                Result.retry()
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync worker error: ${e.message ?: "unknown"}")
            notifySyncFailedDeduped("Sync error: ${e.message ?: "unknown"}")
            Result.retry()
        } finally {
            syncRepo.close()
        }
    }
}
