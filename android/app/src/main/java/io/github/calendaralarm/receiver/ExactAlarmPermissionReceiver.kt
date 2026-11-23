package io.github.calendaralarm.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AppLogger

private const val TAG = "ExactAlarmPermReceiver"

/**
 * P1 fix review#10: receives AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.
 *
 * If the user revokes (or grants) the exact-alarm permission, all
 * alarms must be rescheduled — on revoke schedule() fails (canScheduleExactAlarms=false),
 * on grant they work exactly again. In both cases rescheduleAll() so the
 * AlarmManager state matches the store again.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val can = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            am?.canScheduleExactAlarms() ?: false
        } else true
        AppLogger.i(TAG, "P1 #10: exact-alarm permission changed → canScheduleExactAlarms=$can — rescheduleAll()")
        try {
            AlarmRepository(context).rescheduleAll()
        } catch (e: Exception) {
            AppLogger.e(TAG, "P1 #10: rescheduleAll after permission change failed: ${e.message}")
        }
    }
}
