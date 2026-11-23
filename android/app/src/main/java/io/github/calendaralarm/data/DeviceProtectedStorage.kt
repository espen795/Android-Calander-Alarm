package io.github.calendaralarm.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager

/**
 * P1 fix review#1: Direct Boot — device-protected storage for the alarm path.
 *
 * Problem: before the first unlock after a reboot, credential-encrypted
 * storage (CE) is NOT readable. AlarmStore, AlarmRepositoryState and the non-secret
 * alarm settings lived in regular (CE-bound) SharedPreferences → after a
 * reboot without unlocking, the alarms were unreadable and nothing fired.
 *
 * Solution: these prefs move to DEVICE-PROTECTED (DP) storage
 * (Context.createDeviceProtectedStorageContext()), which is readable before unlock.
 * Secure prefs (device token, CF credentials) stay in CE (credential-protected)
 * — code running during locked boot must not need them.
 *
 * Migration (one-time, in CalendarAlarmApp.onCreate before ALL other pref access):
 * [migrateOnce] copies every prefs file from CE to DP WITHOUT losing data:
 * first moveSharedPreferencesFrom (official API); if that returns false, manual
 * key-by-key copy and only then (on success) clear the CE source. The migration marker
 * lives in DP itself, so a crash halfway through the migration restarts it (idempotent).
 */
object DeviceProtectedStorage {

    private const val TAG = "DeviceProtectedStorage"
    private const val MARKER_PREFS = "calalarm_dp_migration"
    private const val KEY_MIGRATED = "dp_migration_done_v1"

    /**
     * All prefs files that must be in DP (alarm path + timer + widget + funnel +
     * service state — everything that must be readable before the first unlock).
     */
    val DP_PREFS_FILES: List<String> = listOf(
        "calendar_alarm_store",        // AlarmStore (P1 #1)
        "AlarmRepositoryState",     // AlarmRepository state keys (P1 #1)
        "alarm_settings",           // non-secret alarm settings (volume/vibration/sound/button) (P1 #1)
        "calendar_alarm_settings",     // B1 (review): SettingsRepository regular prefs — snooze/freeze/server_url/wake times
        "calalarm_timer_state",        // TimerStateStore (timer survives boot before unlock)
        "calalarm_widget_cache",       // WidgetCache (widget provider runs before unlock)
        "alarm_service_state",      // AlarmForegroundService START_STICKY recovery
        "notif_funnel"              // AlarmNotificationHelper content dedup (used in receivers)
    )

    /**
     * Returns the context to use for DP prefs.
     * On API 24+ = device-protected context; otherwise (very old devices) the app context.
     * Falls back to app context if createDeviceProtectedStorageContext() fails —
     * then we work in CE (degraded, but no crash).
     */
    fun dpContext(context: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return context.applicationContext
        return try {
            context.applicationContext.createDeviceProtectedStorageContext()
        } catch (e: Exception) {
            AppLogger.e(TAG, "createDeviceProtectedStorageContext failed — fallback CE: ${e.message}")
            context.applicationContext
        }
    }

    /** True if the user is currently unlocked (WorkManager may only init then). */
    fun isUserUnlocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                (context.getSystemService(Context.USER_SERVICE) as? UserManager)?.isUserUnlocked ?: true
            } catch (e: Exception) {
                true // fail-open: if we cannot check it, we try (better than no workers)
            }
        } else true
    }

    /**
     * One-time migration CE → DP. Idempotent (marker in DP). Must NEVER lose data.
     * N-b (review): per-KEY merge — existing DP keys NEVER win over CE; only
     * missing CE keys are added. CE is only cleared once ALL CE keys are
     * verified in DP. Called from CalendarAlarmApp.onCreate AND on
     * BOOT_COMPLETED/LOCKED_BOOT/MY_PACKAGE_REPLACED (BootReceiver) AND USER_UNLOCKED
     * (UserUnlockReceiver) — the marker makes repetition safe.
     */
    @Synchronized
    fun migrateOnce(appContext: Context) {
        val dp = dpContext(appContext)
        val markerPrefs = dp.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        if (markerPrefs.getBoolean(KEY_MIGRATED, false)) return

        AppLogger.i(TAG, "P1 #1: start migration to device-protected storage (one-time)")
        var allOk = true
        for (name in DP_PREFS_FILES) {
            try {
                val ce = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                val dpPrefs = dp.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (ce.all.isEmpty()) {
                    AppLogger.d(TAG, "CE '$name' is empty — nothing to migrate")
                    continue
                }
                // N-b (review): per-KEY merge instead of skipping the whole file if DP
                // already contains something. CE does NOT win over DP keys that already exist (DP is the
                // new write source since this version); only missing keys are
                // taken over. CE is only cleared once ALL CE keys are verified in DP.
                val snapshot = ce.all.toMap()
                val editor = dpPrefs.edit()
                var migrated = 0
                for ((k, v) in snapshot) {
                    if (dpPrefs.contains(k)) {
                        // key already exists in DP — DP wins (never overwrite with CE)
                        AppLogger.d(TAG, "N-b: key '$k' in '$name' already exists in DP — DP wins")
                        continue
                    }
                    when (v) {
                        is String -> editor.putString(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is Float -> editor.putFloat(k, v)
                        is Boolean -> editor.putBoolean(k, v)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(k, v as Set<String>)
                        else -> AppLogger.w(TAG, "unknown type for key $k in $name — skipped")
                    }
                    migrated++
                }
                val ok = editor.commit()
                // Verify: ALL CE keys must now be in DP (existing or new)
                val allInDp = snapshot.keys.all { dpPrefs.contains(it) }
                if (ok && allInDp) {
                    ce.edit().clear().commit()
                    AppLogger.i(TAG, "P1 #1/N-b: '$name' migrated CE→DP ($migrated new keys, ${snapshot.size - migrated} already in DP, CE cleared after verification)")
                } else {
                    AppLogger.e(TAG, "P1 #1/N-b: migration of '$name' partial — CE source KEPT (no data loss)")
                    allOk = false
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "P1 #1: migration of '$name' exception — CE kept: ${e.message}")
                allOk = false
            }
        }
        // Only set the marker if everything is ok — on partial failure the
        // migration runs again (idempotent per file: DP-non-empty is skipped).
        if (allOk) {
            markerPrefs.edit().putBoolean(KEY_MIGRATED, true).commit()
            AppLogger.i(TAG, "P1 #1: migration to device-protected storage completed")
        } else {
            AppLogger.w(TAG, "P1 #1: migration partial — marker NOT set, will be retried")
        }
    }

    /** Convenience: DP SharedPreferences for a given file name. */
    fun prefs(context: Context, name: String): SharedPreferences =
        dpContext(context).getSharedPreferences(name, Context.MODE_PRIVATE)
}
