package io.github.calendaralarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.DeviceProtectedStorage

private const val TAG = "UserUnlockReceiver"

/**
 * N-a/N-b (review): ACTION_USER_UNLOCKED — after the first unlock:
 * 1. lazily reinitialize secure prefs (CE) (SettingsRepository also does this on
 *    first use, but here we force it proactively);
 * 2. run the DP migration again (idempotent — marker in DP prevents duplicate work,
 *    but on a migration missed during locked boot this catches it);
 * 3. schedule WorkManager workers (if that was not possible in onCreate yet).
 */
class UserUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_UNLOCKED) return
        AppLogger.i(TAG, "N-a/N-b: USER_UNLOCKED — reinit secure prefs + migration check + workers")
        try {
            DeviceProtectedStorage.migrateOnce(context.applicationContext)
        } catch (e: Exception) {
            AppLogger.e(TAG, "N-b: migration on USER_UNLOCKED failed: ${e.message}")
        }
        try {
            // force re-init of secure prefs (CE now available after unlock)
            val ok = CalendarAlarmApp.settingsRepository.reinitSecurePrefsAfterUnlock()
            AppLogger.i(TAG, "N-a: secure prefs re-init after USER_UNLOCKED — available=$ok")
        } catch (e: Exception) {
            AppLogger.e(TAG, "N-a: secure prefs re-init failed: ${e.message}")
        }
        try {
            (context.applicationContext as? CalendarAlarmApp)?.scheduleWorkersOnceAfterUnlock()
        } catch (e: Exception) {
            AppLogger.e(TAG, "N-a: workers after unlock failed: ${e.message}")
        }
    }
}
