package io.github.calendaralarm.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.calendaralarm.data.UpdateChecker
import io.github.calendaralarm.service.AlarmNotificationHelper

/**
 * Periodic update checker.
 *
 * H-03 fix: Returns Result.retry() on actual errors (not false success).
 * Propagates CancellationException. Always closes the HTTP client.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val WORK_NAME = "calendar_alarm_update_check"
        // v2.6.13 L7: dedup per version — notify only when this version has not been reported yet
        private const val PREFS_NAME = "update_check_prefs"
        private const val KEY_UPDATE_NOTIFIED_VERSION = "update_notified_version"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running update check")

        val updateChecker = UpdateChecker(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            val result = updateChecker.checkForUpdate()

            if (result.error != null) {
                // M-11: don't treat errors as "no update" — retry
                Log.w(TAG, "Update check failed, will retry")
                return Result.retry()
            }

            // v2.6.13 L7: notify ONLY when update_notified_version != latestVersion,
            // and via the central funnel (notifyAlarmEvent UPDATE) instead of directly.
            if (result.updateAvailable && prefs.getString(KEY_UPDATE_NOTIFIED_VERSION, null) != result.latestVersion) {
                Log.i(TAG, "Update available: ${result.latestVersion} (new — notifying)")
                prefs.edit().putString(KEY_UPDATE_NOTIFIED_VERSION, result.latestVersion).apply()
                AlarmNotificationHelper.notifyAlarmEvent(
                    applicationContext,
                    AlarmNotificationHelper.AlarmEvent.UPDATE,
                    time = result.latestVersion ?: "?",
                    label = result.changelog ?: ""
                )
            } else if (result.updateAvailable) {
                Log.d(TAG, "Update ${result.latestVersion} available but already reported — silent")
            } else {
                Log.d(TAG, "No update available")
            }

            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check error, will retry")
            Result.retry()
        } finally {
            updateChecker.close()
        }
    }
}
