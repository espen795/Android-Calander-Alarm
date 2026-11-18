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
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received ${intent.action}, re-arming alarms")
                try {
                    val repo = AlarmRepository(context)
                    repo.rescheduleAll()
                    // v2.3.0: Refresh widget cache + update widget after reboot
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
     * - If the timer already expired (remaining <= 0): fire directly + clear state
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

            val now = System.currentTimeMillis()
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
                // Timer already expired during reboot — fire directly
                AppLogger.i(TAG, "Timer already finished during reboot (remaining=$remaining) — firing now")
                val fireIntent = Intent(context, TimerFireReceiver::class.java).apply {
                    action = TimerScheduler.ACTION_TIMER_FIRED
                }
                try {
                    context.sendBroadcast(fireIntent)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to send timer fire broadcast: ${e.message}")
                }
                // TimerFireReceiver clears the state itself
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to recover timer: ${e.message}")
        }
    }
}
