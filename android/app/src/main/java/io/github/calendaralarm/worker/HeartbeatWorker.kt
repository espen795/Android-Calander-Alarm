package io.github.calendaralarm.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.sync.HeartbeatReporter

/**
 * Phase 4 (hardening plan 2026-11-22, principle 7): periodic heartbeat to the
 * server — "does the phone have the alarm the server expects?"
 *
 * Runs every 3 hours (WorkManager, NetworkType.CONNECTED, only after unlock —
 * scheduled from CalendarAlarmApp.scheduleWorkers, same pattern as the
 * other workers). Freeze does NOT apply to the heartbeat: it does not change
 * alarms, it only reports them.
 *
 * Errors are NEVER fatal: Result.success() even on network error, so no
 * retry storm occurs — the next periodic run tries again.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        const val WORK_NAME = "calendar_alarm_heartbeat"
    }

    /** Test hook: in tests replaced by a fake reporter (no network). */
    var reporterOverride: HeartbeatReporter? = null

    override suspend fun doWork(): Result {
        AppLogger.d(TAG, "Heartbeat run started")
        val reporter = reporterOverride ?: HeartbeatReporter(applicationContext)
        return try {
            val result = reporter.send()
            if (result.success) {
                AppLogger.i(TAG, "Heartbeat sent (${result.scheduledCount} alarms)")
            } else {
                // P4: log-only — no retry, the next periodic run tries again
                AppLogger.w(TAG, "Heartbeat not sent (${result.error}) — next run will retry")
            }
            // Never Result.retry()/failure(): a heartbeat error must not
            // cause a retry storm; the 3-hour periodic run is the next chance.
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Heartbeat worker exception (not fatal): ${e.message}")
            Result.success()
        }
    }
}
