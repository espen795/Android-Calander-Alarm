package io.github.calendaralarm.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmTransactions
import io.github.calendaralarm.data.AppClock
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.DeviceProtectedStorage
import io.github.calendaralarm.service.AlarmNotificationHelper
import io.github.calendaralarm.sync.HeartbeatReporter
import io.github.calendaralarm.sync.SyncRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
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

    /**
     * P2A B2 (Opus review): test hook — in tests replaced by a fake fetcher,
     * so doWork() is end-to-end testable without network. Production: null →
     * [SyncRepositoryFetcher].
     */
    var fetcherOverride: AlarmPlanFetcher? = null

    /**
     * P4: test hook — in tests replaced by a fake reporter, so it can be tested
     * that a successful sync triggers a heartbeat without real network.
     * Production: null → real [io.github.calendaralarm.sync.HeartbeatReporter].
     */
    var heartbeatReporterOverride: io.github.calendaralarm.sync.HeartbeatReporter? = null

    /**
     * Phase 4: heartbeat after a sync run (successful or partially failed). Never fatal and
     * does not change the sync Result. Bounded by the client's 15s request timeout.
     */
    private suspend fun sendHeartbeatSafely() {
        try {
            val hbResult = (heartbeatReporterOverride ?: HeartbeatReporter(applicationContext)).send()
            if (hbResult.success) {
                AppLogger.i(TAG, "P4: heartbeat after sync OK (${hbResult.scheduledCount} alarms)")
            } else {
                AppLogger.w(TAG, "P4: heartbeat after sync failed (${hbResult.error}) — periodic run retries")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "P4: heartbeat after sync exception (not fatal): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AlarmSyncWorker"
        const val WORK_NAME = "calendar_alarm_sync"
        /** P2B #14: unique work name for the manual sync from MainScreen. */
        const val MANUAL_WORK_NAME = "calalarm_manual_sync"
        /** P2B #14: input-data flag — manual sync (user tapped 'Sync now') skips the freeze check. */
        const val INPUT_MANUAL = "manual"
        private const val PREFS_NAME = SyncFailurePolicy.PREFS_NAME
        private const val KEY_LAST_WAKE_TIME = "last_wake_time"
        private const val KEY_LAST_WAKE_DATE = "last_wake_date"

        /**
         * P2A #12: before this time (local) TODAY is also synced — today's wake time
         * can still lie in the future then. After 12:00, "today" is already past
         * for every wake time.
         */
        private const val SYNC_TODAY_UNTIL_HOUR = 12

        /** P2A #13: ISO date YYYY-MM-DD — strict regex (LocalDate.parse alone is not enough). */
        private val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        /** P2A #13: wake time HH:mm 00:00–23:59. */
        private val WAKE_TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        /**
         * P2A #13: validates plan date and wake time from the server.
         * @return the parsed date, or null if date OR wake time is invalid.
         * On null the caller must NOT overwrite anything and return Result.retry().
         */
        fun validatePlan(planDate: String, wakeTime: String?): LocalDate? {
            if (!DATE_REGEX.matches(planDate)) return null
            val parsed = try {
                LocalDate.parse(planDate, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                return null
            }
            // LocalDate.parse("2026-02-30") already throws, but check explicitly (fail-safe):
            if (parsed.toString() != planDate) return null
            if (wakeTime != null && !WAKE_TIME_REGEX.matches(wakeTime)) return null
            return parsed
        }

        /**
         * P2A #12: which dates should this run sync? Always tomorrow; before 12:00 local
         * also today. Tested in AlarmSyncDatesTest.
         */
        fun datesToSync(nowMillis: Long = AppClock.now(), zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> {
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
            val today = now.toLocalDate()
            val tomorrow = today.plusDays(1)
            return if (now.hour < SYNC_TODAY_UNTIL_HOUR) listOf(today, tomorrow) else listOf(tomorrow)
        }

        /**
         * P2A #12: true if the wake time on [date] still lies in the future at this moment
         * (otherwise syncing that date is pointless — replaceForDate refuses past times
         * anyway, but we log it explicitly and never schedule anything in the past).
         */
        fun isWakeTimeStillPossible(date: LocalDate, wakeTime: String, nowMillis: Long = AppClock.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
            val localTime = try { LocalTime.parse(wakeTime) } catch (e: Exception) { return false }
            val trigger = ZonedDateTime.of(date, localTime, zone).toInstant().toEpochMilli()
            return trigger > nowMillis
        }
    }

    /** DP prefs (readable before unlock) — persistent sync state incl. fail counter. */
    private fun syncPrefs() =
        DeviceProtectedStorage.prefs(applicationContext, PREFS_NAME)

    /**
     * P2A #6: SYNC_FAILED notification via [SyncFailurePolicy] — counter in device-protected
     * prefs, notify after ≥3 consecutive failed runs, then max 1×/12h, reset on success.
     * (Was: per-instance `runAttemptCount` → WorkManager creates a new
     * worker per run, so the counter was always 0 and SYNC_FAILED was NEVER notified.)
     */
    private fun notifySyncFailedDeduped(reason: String) {
        val prefs = syncPrefs()
        val decision = SyncFailurePolicy.onFailure(prefs, AppClock.now(), reason)
        SyncFailurePolicy.record(prefs, decision)
        if (decision.notify) {
            AlarmNotificationHelper.notifyAlarmEvent(
                applicationContext,
                AlarmNotificationHelper.AlarmEvent.SYNC_FAILED,
                label = reason
            )
        }
    }

    override suspend fun doWork(): Result {
        AppLogger.i(TAG, "Starting alarm sync (pull from server)")

        // v2.5.8: Alarm Freeze blocks AUTOMATED sync only
        // User-initiated actions in the UI are always allowed
        // Skip automated sync during freeze hours — try again next run.
        // P2B #14: a MANUAL sync (user tapped "Sync now" → input manual=true)
        // is NOT blocked by the freeze — same behavior as the old
        // manual sync in MainScreen (freeze only blocked the automated one there).
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        val settings = io.github.calendaralarm.CalendarAlarmApp.settingsRepository
        if (settings.isWekkerFrozen() && !manual) {
            AppLogger.i(TAG, "Automated sync skipped: ${settings.getWekkerFreezeMessage()}")
            return Result.success() // Skip silently — try again next hour
        }
        if (manual) {
            AppLogger.i(TAG, "P2B #14: manual sync (manual=true) — freeze check skipped")
        }

        val fetcher = fetcherOverride ?: SyncRepositoryFetcher(applicationContext)
        val alarmRepo = AlarmRepository(applicationContext)
        val prefs = syncPrefs()

        return try {
            // Check exact alarm permission first
            if (!alarmRepo.canScheduleExactAlarms()) {
                Log.w(TAG, "Exact alarm permission not granted — cannot schedule")
                notifySyncFailedDeduped("Grant permission for exact alarms in the app settings")
                return Result.failure()
            }

            // P2A #12: sync TODAY (before 12:00 local, as long as today's wake time
            // can still lie in the future) AND TOMORROW. Server API: GET /api/alarm/next?date=YYYY-MM-DD.
            val dates = datesToSync()
            AppLogger.i(TAG, "P2A #12: syncing dates: $dates")

            var anyFailure = false
            var lastError: String? = null

            for (date in dates) {
                val dateStr = date.toString()
                val syncResult = fetcher.fetchAlarmPlan(dateStr)

                if (!syncResult.success || syncResult.plan == null) {
                    Log.e(TAG, "Sync failed for $dateStr: ${syncResult.error}")
                    // 503 from server → fetchAlarmPlan returns success=false without
                    // overwriting anything: the existing alarm is preserved and we retry.
                    anyFailure = true
                    lastError = syncResult.error ?: "Unknown error"
                    continue
                }

                val plan = syncResult.plan

                // P2A #13: validate date AND wake time — invalid → do NOT overwrite anything, retry.
                // (Was: fallback to "tomorrow" — a broken server value could thus
                // schedule a wrong alarm.)
                val targetDate = validatePlan(plan.date, plan.wakeTime)
                if (targetDate == null) {
                    AppLogger.e(TAG, "P2A #13: invalid plan from server (date='${plan.date}', wake_time='${plan.wakeTime}') — do NOT overwrite anything, retry")
                    anyFailure = true
                    lastError = "Invalid plan from server (date/time)"
                    continue
                }
                if (targetDate.toString() != dateStr) {
                    AppLogger.e(TAG, "P2A #13: server gave plan for ${plan.date} while $dateStr was requested — do NOT overwrite anything, retry")
                    anyFailure = true
                    lastError = "Server gave plan for wrong date"
                    continue
                }

                // P2A #12: today's wake time in the past → skip (never schedule an alarm
                // in the past; replaceForDate refuses this itself as well).
                val isToday = targetDate == dates.first() && dates.size > 1
                if (plan.wakeTime != null && isToday &&
                    !isWakeTimeStillPossible(targetDate, plan.wakeTime)
                ) {
                    AppLogger.i(TAG, "P2A #12: wake time ${plan.wakeTime} on $dateStr already lies in the past — skipping")
                    continue
                }

                // P2A B1 (Opus review, blocker): for TODAY the sync may ONLY
                // UPDATE an existing, still pending smart alarm or (on explicit "off")
                // cancel it — NEVER create a new alarm and NEVER touch a snooze/fired/
                // disabled record. Reason: an alarm the user turned off this
                // morning (pre-alarm "Turn off"/dismiss delete the
                // record → no userDisabled trail) or one that already fired, must not
                // be restored; and a running snooze record must not be cleaned up.
                // P2B review#9: the whole check+apply per date runs inside the global
                // alarm mutex (fetch itself already OUTSIDE the lock — no network inside the lock).
                // P2B re-review: the guard is re-evaluated INSIDE the same lock as the apply
                // (todayBlocked), so a "Turn off" between check and apply
                // does not lead to a restored alarm.
                fun todayBlocked(): Boolean {
                    if (!isToday) return false
                    val existingToday = alarmRepo.store.getAll().filter {
                        it.date == dateStr && it.type != "manual" && it.type != "test"
                    }
                    val pending = existingToday.filter {
                        it.enabled && !it.userDisabled && !it.isSnooze && it.triggerAtMillis > AppClock.now()
                    }
                    val blocked = pending.isEmpty() || existingToday.any { it.isSnooze }
                    if (blocked) {
                        AppLogger.i(TAG, "P2A B1: today ($dateStr) no pending smart alarm (pending=${pending.size}, snooze=${existingToday.any { it.isSnooze }}) — sync creates nothing and cancels nothing (plan=${plan.wakeTime ?: "off"})")
                    }
                    return blocked
                }
                // (no separate check outside the lock: todayBlocked() is only evaluated within the
                //  apply locks below — single source of truth, but tested)

                // No alarm (e.g. weekend without weekend rule) → cancel existing alarms for that day
                if (plan.wakeTime == null) {
                    AppLogger.i(TAG, "No alarm for ${plan.date} (${plan.label}) — cancelling existing")
                    // P2B review#9: diff + apply atomic within the alarm mutex
                    val (beforeSize, cancelled) = AlarmTransactions.run {
                        if (todayBlocked()) return@run 0 to 0
                        val before = alarmRepo.store.getAll().filter { it.date == targetDate.toString() }
                        // v2.6.13 L4: force=true → per-alarm REMOVED is silent; worker itself notifies 1× aggregate
                        val n = alarmRepo.cancelForDate(targetDate, force = true)  // reentrant
                        before.size to n
                    }
                    if (cancelled > 0) {
                        // E3: actually removed → one notification
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
                    AppLogger.i(TAG, "Cancelled $cancelled alarm(s) for ${plan.date} (before=$beforeSize)")
                    continue
                }

                // v2.6.13 L1: compute the event BEFORE apply — the worker is the ONLY notifier.
                // SET = there was no enabled smart alarm yet for this date;
                // CHANGED = time or label differed; null = identical → silent.
                // v2.6.13 H3: a user-disabled alarm (userDisabled=true) does NOT count as
                // "already exists" — the sync must not re-enable the user choice or
                // report it as a change. If ALL existing alarms are user-disabled → event=null (silent),
                // because replaceForDate leaves them disabled (see H3 guard there).
                // P2B review#9: event computation + apply within one lock — no lost update
                // with a concurrent UI action (e.g. user disables the alarm while sync runs).
                val planDateStr = targetDate.toString()
                var blockedInLock = false
                val (event, scheduled) = AlarmTransactions.run {
                    if (todayBlocked()) { blockedInLock = true; return@run null to null }
                    val existingForDate = alarmRepo.store.getAll().filter { it.date == planDateStr && it.type != "manual" && it.type != "test" }
                    val activeForDate = existingForDate.filter { !it.userDisabled }
                    val ev = when {
                        existingForDate.isNotEmpty() && activeForDate.isEmpty() && existingForDate.all { it.userDisabled } ->
                            null  // v2.6.13 H3: all user-disabled → sync leaves it alone, silent
                        activeForDate.none { it.enabled } -> AlarmNotificationHelper.AlarmEvent.SET
                        activeForDate.any { it.timeLabel != plan.wakeTime || it.label != plan.label } ->
                            AlarmNotificationHelper.AlarmEvent.CHANGED
                        else -> null   // identical → silent ("set to 10, still 10")
                    }

                    // Schedule the alarm (replace any existing smart alarm for this date).
                    // v2.6.13 L1: silent=true → replaceForDate notifies nothing (idempotent, ID reused);
                    // the worker below notifies ONLY if event != null.
                    val sched = alarmRepo.replaceForDate(
                        date = targetDate,
                        timeLabel = plan.wakeTime,
                        label = plan.label,
                        type = plan.workLocation,
                        silent = true
                    )  // reentrant within the lock
                    ev to sched
                }

                if (blockedInLock) continue
                if (scheduled != null) {
                    prefs.edit()
                        .putString(KEY_LAST_WAKE_TIME, plan.wakeTime)
                        .putString(KEY_LAST_WAKE_DATE, plan.date)
                        .apply()
                    // v2.6.13 L1: worker is the ONLY notifier — 1 notification on SET/CHANGED, silent when identical
                    event?.let {
                        AppLogger.i(TAG, "Alarm event $it for $planDateStr: -> ${plan.wakeTime} (${plan.label})")
                        AlarmNotificationHelper.notifyAlarmEvent(
                            applicationContext,
                            it,
                            time = plan.wakeTime,
                            label = plan.label,
                            alarmId = scheduled.id
                        )
                        AppLogger.i(TAG, "Sync complete for $planDateStr: event=$it — 1 notification")
                    } ?: AppLogger.i(TAG, "Sync complete for $planDateStr: identical plan — silent (no notification)")
                } else {
                    AppLogger.e(TAG, "Failed to schedule alarm from plan for $planDateStr")
                    anyFailure = true
                    lastError = "Could not schedule alarm"
                }
            }

            if (anyFailure) {
                notifySyncFailedDeduped(lastError ?: "Sync error")
                // P4 review B2: also after a (partially) failed sync report what the phone now has,
                // otherwise the plan cache is newer than the heartbeat.
                sendHeartbeatSafely()
                // P2B re-review B1: never retry a manual sync — otherwise the UI stays
                // "busy" and a retry (with manual=true) later slips through the freeze.
                return if (manual) Result.failure() else Result.retry()
            }

            // P2A #6: success → reset the PERSISTENT fail counter
            SyncFailurePolicy.onSuccess(prefs)

            sendHeartbeatSafely()
            Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync worker error: ${e.message ?: "unknown"}")
            notifySyncFailedDeduped("Sync error: ${e.message ?: "unknown"}")
            sendHeartbeatSafely()  // P4 re-review: also in this path, so "pending" resolves immediately
            if (manual) Result.failure() else Result.retry()
        } finally {
            fetcher.close()
        }
    }
}
