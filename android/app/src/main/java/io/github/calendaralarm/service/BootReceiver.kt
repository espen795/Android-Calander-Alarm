package io.github.calendaralarm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerScheduler
import io.github.calendaralarm.data.TimerStateStore
import io.github.calendaralarm.receiver.TimerFireReceiver

private const val TAG = "BootReceiver"

/**
 * Re-arms persisted alarms after boot or app update.
 *
 * Pull model: no foreground service to start. We only need to re-schedule
 * alarms that were persisted before the reboot (AlarmManager alarms are
 * cleared on reboot).
 *
 * v2.5.6 Story 5: Timer recovery after reboot:
 * - Check TimerStateStore for an active timer
 * - If remaining > 0: re-schedule via TimerScheduler
 * - If remaining <= 0: fire TimerFireReceiver directly + clear state
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,  // P1 review#1: Direct Boot — before first unlock
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received ${intent.action}, re-arming alarms")
                // N-b (review): migrateOnce also on BOOT_COMPLETED/LOCKED_BOOT/REPLACED —
                // Application.onCreate also runs on a receiver start, but if that
                // failed/crashed the migration must get another chance here before rescheduleAll
                // (otherwise AlarmStore reads from empty CE prefs during locked boot).
                try {
                    io.github.calendaralarm.data.DeviceProtectedStorage.migrateOnce(context.applicationContext)
                } catch (e: Exception) {
                    Log.e(TAG, "N-b: DP migration on ${intent.action} failed (continuing): ${e.message}")
                }
                try {
                    val repo = AlarmRepository(context)
                    repo.rescheduleAll()
                    // v2.3.0: Refresh widget cache + update widget na reboot
                    repo.refreshWidget()
                    Log.i(TAG, "Alarms re-armed: ${repo.store.getEnabled().size} active")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-arm alarms")
                }

                // v2.5.6 Story 5: Timer recovery after reboot
                recoverTimer(context)
            }
            else -> {
                Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            }
        }
    }

    /**
     * v2.5.6 Story 5: Restore the timer after a reboot.
     *
     * AlarmManager alarms are cleared on reboot, so we must re-schedule the timer
     * based on the saved TimerState.
     *
     * - If the timer is still running (remaining > 0): re-schedule via TimerScheduler
     * - If the timer has already finished (remaining <= 0): fire directly + clear state
     * - If the timer was paused: do nothing (no AlarmManager entry needed)
     */
    private fun recoverTimer(context: Context) {
        try {
            val stateStore = TimerStateStore(context)
            val savedState = stateStore.load() ?: return

            if (!savedState.isRunning) {
                // Paused timer — no AlarmManager entry needed
                AppLogger.i(TAG, "Timer was paused, no re-schedule needed")
                return
            }

            val now = io.github.calendaralarm.data.AppClock.now()
            val remaining = savedState.endTimeWallClock - now

            if (remaining > 0) {
                // Timer still running — re-schedule AlarmManager
                val scheduler = TimerScheduler(context)
                val scheduled = scheduler.schedule(savedState.endTimeWallClock)
                if (scheduled) {
                    AppLogger.i(TAG, "Timer re-scheduled after boot: ${remaining}ms remaining")
                } else {
                    AppLogger.e(TAG, "Failed to re-schedule timer after boot")
                }
            } else {
                // Timer already finished during reboot — fire directly
                AppLogger.i(TAG, "Timer already finished during reboot (remaining=$remaining) — firing now")
                // v2.7.0: same idempotent entry point as AlarmManager/ViewModel
                TimerFireReceiver.fire(context, source = "boot")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to recover timer: ${e.message}")
        }
    }
}
