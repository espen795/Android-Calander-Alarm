package io.github.calendaralarm

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.calendaralarm.data.SettingsRepository
import io.github.calendaralarm.worker.AlarmSyncWorker
import io.github.calendaralarm.worker.UpdateCheckWorker
import java.util.concurrent.TimeUnit

/**
 * Application class.
 *
 * Pull model: the app is a CLIENT. It polls the Calendar Alarm server via HTTPS.
 * No in-app HTTP server — less attack surface, no VPN/inbound firewall needed.
 *
 * Schedules:
 * - AlarmSyncWorker: hourly + once on startup → checks for alarm plan changes
 * - UpdateCheckWorker: every 6 hours → checks for app updates
 */
class CalendarAlarmApp : Application() {

    companion object {
        private const val TAG = "CalendarAlarmApp"

        lateinit var instance: CalendarAlarmApp
            private set

        lateinit var settingsRepository: SettingsRepository
            private set

        const val CHANNEL_ALARMS = "alarms"
        const val CHANNEL_ALARMS_RINGING = "alarm_ringing_v3"  // v2.6.9: New channel for fullscreen alarm (IMPORTANCE_HIGH + bypass DND)
        const val CHANNEL_UPDATES = "updates"
        const val CHANNEL_TIMER = "timer"  // v2.5.3: Timer ongoing notification
        const val NOTIFICATION_ID_ALARM = 2001
        const val NOTIFICATION_ID_UPDATE = 3001
        const val NOTIFICATION_ID_RINGING = 4001
        const val NOTIFICATION_ID_ALARM_SERVICE = 4100 // v2.5.7 PM fix CRIT-01: was 4002 (collided with 4001+alarmId=1), now 4100 (safe range)
        const val NOTIFICATION_ID_TIMER = 5001        // v2.5.3: Timer ongoing notification (TimerScreen UI)
        const val NOTIFICATION_ID_TIMER_SERVICE = 5002 // v2.5.6 PM fix C-02: TimerForegroundService notification
        const val NOTIFICATION_ID_TIMER_FIRED = 5003   // v2.5.6 PM fix C-02: TimerFireReceiver "expired" notification

        // v2.6.0: SharedPreferences keys for configurable alarm options
        const val PREFS_ALARM_SETTINGS = "alarm_settings"
        const val PREF_VOLUME_START_LEVEL = "volume_start_level"           // Float 0.0-1.0
        const val PREF_VOLUME_BUILD_UP_DURATION = "volume_build_up_duration" // Long in ms
        const val PREF_VIBRATION_ENABLED = "vibration_enabled"             // Boolean
        const val PREF_VIBRATION_PATTERN = "vibration_pattern"             // String "0,1000,500"
        const val PREF_ALARM_SOUND_URI = "alarm_sound_uri"                 // String Uri
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)

        // v2.5.10: Migrate settings from encrypted to regular SharedPreferences (one-time)
        // This fixes the bug where snooze/freeze settings were not persisted on Samsung devices
        try {
            val migrated = settingsRepository.migrateFromEncryptedPrefs()
            if (migrated) {
                io.github.calendaralarm.data.AppLogger.i("Lifecycle", "Settings migration v2.5.10 complete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Settings migration failed: ${e.message}")
        }

        io.github.calendaralarm.data.AppLogger.i("Lifecycle", "App onCreate (v${BuildConfig.VERSION_NAME})")

        createNotificationChannels()

        if (!settingsRepository.secureStorageAvailable) {
            Log.e(TAG, "CRITICAL: Secure storage unavailable.")
        } else {
            Log.i(TAG, "Secure storage available — tokens/CF credentials OK")
        }

        scheduleWorkers()
    }

    private fun scheduleWorkers() {
        try {
            // v2.5.8: Hourly sync — check for alarm changes every hour
            // Hourly checks (outside Alarm Freeze window).
            // Silent when no changes (implemented in AlarmSyncWorker).
            // Freeze skip logic is in AlarmSyncWorker (Story 1).
            val syncPeriodic = PeriodicWorkRequestBuilder<AlarmSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                AlarmSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncPeriodic
            )
            Log.i(TAG, "Alarm sync scheduled: hourly")

            // Immediate sync on app start (so the alarm is set right away if needed).
            // v2.6.13 L2: throttle — max 1× per 30 min (every process start used to trigger a
            // sync; after the L1 fix that is silent, but it remains an unnecessary trigger), and KEEP
            // instead of REPLACE so an already running/queued immediate sync is not aborted.
            val syncPrefs = getSharedPreferences("alarm_sync_prefs", android.content.Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - syncPrefs.getLong("last_immediate_sync", 0) > 30 * 60_000L) {
                syncPrefs.edit().putLong("last_immediate_sync", now).apply()
                val syncImmediate = OneTimeWorkRequestBuilder<AlarmSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(this).enqueueUniqueWork(
                    "${AlarmSyncWorker.WORK_NAME}_immediate",
                    ExistingWorkPolicy.KEEP,
                    syncImmediate
                )
                Log.i(TAG, "Immediate sync scheduled (throttled: 1×/30min)")
            } else {
                Log.i(TAG, "Immediate sync skipped (throttle 30 min — ran recently)")
            }

            // Update check: every 6 hours
            val updateCheck = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                UpdateCheckWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                updateCheck
            )

            // H-05 fix: Log upload reduced from 15min to 6 hours (was too frequent for privacy)
            val logUpload = PeriodicWorkRequestBuilder<io.github.calendaralarm.worker.LogUploadWorker>(
                6, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                io.github.calendaralarm.worker.LogUploadWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                logUpload
            )

            Log.i(TAG, "Workers scheduled: sync (hourly + immediate), update check (6h), log upload (6h — was 15min)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule workers")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            NotificationChannel(
                CHANNEL_ALARMS,
                getString(R.string.channel_alarms),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarm notifications"
                nm.createNotificationChannel(this)
            }

            // v2.6.9: New channel for ringing alarm — IMPORTANCE_HIGH + bypass DND for fullscreen intent
            NotificationChannel(
                CHANNEL_ALARMS_RINGING,
                "Alarm Ringtone (Fullscreen)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fullscreen alarm notifications when the alarm rings"
                setBypassDnd(true)  // v2.6.9: Bypass Do Not Disturb for critical alarms
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                nm.createNotificationChannel(this)
            }

            // v2.5.9: Was IMPORTANCE_DEFAULT, now HIGH for visibility (download completion notifications)
            NotificationChannel(
                CHANNEL_UPDATES,
                getString(R.string.channel_updates),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "App update notifications"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
                nm.createNotificationChannel(this)
            }

            // v2.5.3: Timer channel — low priority ongoing notification while timer runs
            NotificationChannel(
                CHANNEL_TIMER,
                getString(R.string.channel_timer),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Timer countdown notifications"
                nm.createNotificationChannel(this)
            }
        }
    }
}
