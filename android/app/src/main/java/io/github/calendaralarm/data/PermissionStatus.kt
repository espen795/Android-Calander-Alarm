package io.github.calendaralarm.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Permission status of the app — Phase 4 (principle 7) moved from
 * ui/screens/PermissionsCard.kt to this data package, so that the
 * HeartbeatReporter (worker, no UI) can reuse the same source.
 *
 * - Recomputed on every call of [read] (the UI recomputes on
 *   ON_RESUME, the worker on every heartbeat run).
 * - Notifications (POST_NOTIFICATIONS): without this permission the
 *   full-screen notifications of alarm and timer are silently dropped.
 */
data class PermissionStatus(
    val notifications: Boolean,
    val fullScreen: Boolean,
    val overlay: Boolean,
    val batteryUnrestricted: Boolean,
    val exactAlarms: Boolean
) {
    /** Critical for reliable firing (alarm + timer). */
    val criticalOk: Boolean get() = notifications && fullScreen && exactAlarms
    val allOk: Boolean get() = criticalOk && overlay && batteryUnrestricted

    companion object {
        fun read(context: Context): PermissionStatus {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return PermissionStatus(
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent(),
                overlay = Settings.canDrawOverlays(context),
                batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName),
                exactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            )
        }
    }
}
