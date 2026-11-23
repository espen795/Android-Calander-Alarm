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
import io.github.calendaralarm.worker.HeartbeatWorker
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
        // v2.7.0: Timer finished — IMPORTANCE_HIGH (required for full-screen intent), no
        // channel sound (TimerRingService plays the alarm sound itself via MediaPlayer).
        const val CHANNEL_TIMER_RINGING = "timer_ringing_v1"
        // P1 review#17: notification IDs live in the central register NotificationIds.
        // These constants remain as delegates (backwards-compat for existing
        // references), but NEW code must use NotificationIds directly.
        const val NOTIFICATION_ID_ALARM = io.github.calendaralarm.service.NotificationIds.ALARM
        const val NOTIFICATION_ID_UPDATE = io.github.calendaralarm.service.NotificationIds.UPDATE
        // NB: NOTIFICATION_ID_RINGING was 4001 — the per-alarm ringing ID is NOW in
        // NotificationIds.ringing(alarmId) = 4200 + alarmId (own block, no collision).
        const val NOTIFICATION_ID_RINGING = 4001 // legacy base — ONLY for cancel fallback; use NotificationIds.ringing(id)
        const val NOTIFICATION_ID_ALARM_SERVICE = io.github.calendaralarm.service.NotificationIds.ALARM_SERVICE
        const val NOTIFICATION_ID_TIMER = io.github.calendaralarm.service.NotificationIds.TIMER
        const val NOTIFICATION_ID_TIMER_SERVICE = io.github.calendaralarm.service.NotificationIds.TIMER_SERVICE
        const val NOTIFICATION_ID_TIMER_FIRED = io.github.calendaralarm.service.NotificationIds.TIMER_FIRED
        const val NOTIFICATION_ID_TIMER_RINGING = io.github.calendaralarm.service.NotificationIds.TIMER_RINGING

        // v2.6.0: SharedPreferences keys for configurable alarm options
        const val PREFS_ALARM_SETTINGS = "alarm_settings"
        const val PREF_VOLUME_START_LEVEL = "volume_start_level"           // Float 0.0-1.0
        const val PREF_VOLUME_BUILD_UP_DURATION = "volume_build_up_duration" // Long in ms
        const val PREF_VIBRATION_ENABLED = "vibration_enabled"             // Boolean
        const val PREF_VIBRATION_PATTERN = "vibration_pattern"             // String "0,1000,500"
        const val PREF_ALARM_SOUND_URI = "alarm_sound_uri"                 // String Uri
        // v2.7.0: What do physical buttons (volume/power) do during a ringing alarm
        const val PREF_HARDWARE_BUTTON_ACTION = "hardware_button_action"   // "dismiss" | "snooze"
        const val HARDWARE_ACTION_DISMISS = "dismiss"
        const val HARDWARE_ACTION_SNOOZE = "snooze"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // P1 review#1 (Direct Boot): migration to device-protected storage must happen BEFORE
        // all other pref access — the alarm receivers/services read their
        // prefs in DP. A crash here would block ALL alarms, so wrap everything in try/catch.
        try {
            io.github.calendaralarm.data.DeviceProtectedStorage.migrateOnce(this)
        } catch (e: Exception) {
            Log.e(TAG, "P1 #1: DP migration exception (continuing in CE — degraded): ${e.message}")
        }
        settingsRepository = SettingsRepository(this)

        // P1 review#2: on detected corruption of the alarm store, inform the user
        // ("Alarm store corrupted — open the app"). Install BEFORE the first
        // AlarmStore access. Shielded with try/catch: a crash here in onCreate
        // would block ALL alarms (see hardening plan, Direct Boot warning).
        try {
            io.github.calendaralarm.data.AlarmStore.onCorruption = {
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val ch = NotificationChannel(
                            CHANNEL_ALARMS, getString(R.string.channel_alarms),
                            NotificationManager.IMPORTANCE_HIGH
                        )
                        nm.createNotificationChannel(ch)
                    }
                    val pi = android.app.PendingIntent.getActivity(
                        this, 0, android.content.Intent(this, MainActivity::class.java),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val n = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ALARMS)
                        .setContentTitle("⚠️ Alarm store corrupted")
                        .setContentText("Open the app — the stored alarms could not be read")
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .build()
                    nm.notify(io.github.calendaralarm.service.NotificationIds.STORE_CORRUPT, n)
                } catch (e: Exception) {
                    Log.e(TAG, "P1: posting corruption notification failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "P1: installing onCorruption failed: ${e.message}")
        }

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

        io.github.calendaralarm.data.AppLogger.i("Lifecycle", "App onCreate (v${BuildConfig.VERSION_NAME}) — userUnlocked=${io.github.calendaralarm.data.DeviceProtectedStorage.isUserUnlocked(this)}")

        createNotificationChannels()

        if (!settingsRepository.secureStorageAvailable) {
            Log.e(TAG, "CRITICAL: Secure storage unavailable.")
        } else {
            Log.i(TAG, "Secure storage available — tokens/CF credentials OK")
        }

        // P1 review#1 (Direct Boot): initializing WorkManager BEFORE the first
        // unlock crashes (CE storage unavailable) → only schedule AFTER unlock.
        // During locked boot only the alarm receivers/service run (DP storage).
        if (io.github.calendaralarm.data.DeviceProtectedStorage.isUserUnlocked(this)) {
            scheduleWorkers()
        } else {
            Log.i(TAG, "P1 #1: user locked (Direct Boot) — WorkManager deferred until after unlock")
        }
    }

    // P1 review#1: after the first unlock the workers are scheduled anyway —
    // once per process (guard in prefs, DP so it also works correctly after
    // a locked-boot start).
    @Volatile private var workersScheduledAfterUnlock = false
    fun scheduleWorkersOnceAfterUnlock() {
        if (workersScheduledAfterUnlock) return
        workersScheduledAfterUnlock = true
        Log.i(TAG, "P1 #1: user unlocked — scheduling WorkManager workers now")
        scheduleWorkers()
    }

    private fun scheduleWorkers() {
        try {
            // v2.5.8: Hourly sync — check for alarm changes every hour
            // Hourly checks (outside the Alarm Freeze window).
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
            // v2.6.13 L2: throttle — max 1× per 30 min (every process start first triggered a
            // sync; after the L1 fix that is silent, but it remains an unnecessary trigger), and KEEP
            // instead of REPLACE so an already running/queued immediate sync is not aborted.
            val syncPrefs = getSharedPreferences("alarm_sync_prefs", android.content.Context.MODE_PRIVATE)
            val now = io.github.calendaralarm.data.AppClock.now()
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
                Log.i(TAG, "Immediate sync skipped (throttle 30 min — already ran recently)")
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

            // H-05 fix: Log upload lowered from 15min to 6 hours (was too frequent for privacy)
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

            // Phase 4 (principle 7): heartbeat — every 3 hours report which alarms are
            // scheduled + permission status, so the server can signal when the
            // phone has NO alarm where the server expects one.
            // Freeze does NOT apply (heartbeat does not change alarms). Only
            // scheduled after unlock (same pattern as the other workers).
            val heartbeat = PeriodicWorkRequestBuilder<HeartbeatWorker>(3, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                HeartbeatWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                heartbeat
            )

            Log.i(TAG, "Workers scheduled: sync (hourly + immediate), update check (6h), log upload (6h — was 15min), heartbeat (3h)")
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
                "Alarm Ringing (Fullscreen)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fullscreen alarm notifications when the alarm goes off"
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

            // v2.7.0: Timer finished — HIGH so the full-screen intent works (LOW ignores FSI).
            // No channel sound: TimerRingService plays the alarm sound itself.
            NotificationChannel(
                CHANNEL_TIMER_RINGING,
                "Timer finished",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification + full screen when the timer expires"
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(this)
            }
        }
    }
}
