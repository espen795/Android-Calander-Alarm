package io.github.calendaralarm.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import io.github.calendaralarm.data.AppLogger

private const val TAG = "PowerButtonWatcher"

/**
 * v2.7.0: Detects a press of the power button while an alarm or timer is ringing.
 *
 * Android 15 / One UI does not deliver KEYCODE_POWER to apps (the system intercepts it),
 * which is why "power = dismiss" never worked in the activity. What does arrive
 * reliably is ACTION_SCREEN_OFF — the standard approach of clock apps.
 *
 * To prevent a screen timeout from counting as a button press, the watcher holds a
 * screen wake lock while ringing: the screen can then only turn off via the power button.
 * A short grace period after start ignores screen transitions caused by the alarm firing itself.
 *
 * Works independently of the activity: also when the full-screen intent only shows a heads-up
 * (phone in use) or the activity is not in the foreground.
 */
class PowerButtonWatcher(
    private val context: Context,
    private val onPowerButton: () -> Unit,
    // P1 review#8: optional: callback that determines WHICH action a button press performs.
    // Default = always onPowerButton (old behavior). AlarmForegroundService passes
    // a lambda here that on SNOOZE_INSTEAD_OF_DISMISS sends snooze instead of dismiss.
    private val onPowerButtonDecision: ((PowerButtonDecision.Outcome) -> Unit)? = null,
    // P1 review#8: how long the alarm has been ringing (ms) — needed for the <10s snooze guard
    private val ringingForMs: () -> Long = { 0L }
) {
    private var receiver: BroadcastReceiver? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var startedAt = 0L
    private var screenOnAt = 0L

    @Synchronized
    fun start() {
        if (receiver != null) return
        startedAt = SystemClock.elapsedRealtime()
        screenOnAt = startedAt
        acquireScreenLock()

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // N-g (review): update screenOnAt on SCREEN_ON — otherwise guard 2
                // (screen at least 1.5s on) sticks to the start value if the screen
                // turned on again in between (proximity vibration). The 10s rule is the real
                // protection; guard 2 is an extra heuristic.
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    screenOnAt = SystemClock.elapsedRealtime()
                    AppLogger.d(TAG, "SCREEN_ON — screenOnAt updated")
                    return
                }
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                val sinceStart = SystemClock.elapsedRealtime() - startedAt
                if (sinceStart < GRACE_MS) {
                    AppLogger.i(TAG, "SCREEN_OFF within grace (${sinceStart}ms) — ignored")
                    return
                }
                // P1 review#8: guard — only count as button action when the app
                // actually holds the screen wake lock AND the screen was
                // on for at least 1.5 s before that. Otherwise (proximity, system ignores wakelock)
                // we ignore the SCREEN_OFF (the alarm must NOT turn off).
                val wakeLockHeld = screenLock?.isHeld == true
                val screenWasOnForMs = SystemClock.elapsedRealtime() - screenOnAt
                val configuredAction = readConfiguredAction()
                val outcome = PowerButtonDecision.shouldCountAsPowerButton(
                    screenWakeLockHeld = wakeLockHeld,
                    screenWasOnForMs = screenWasOnForMs,
                    ringingForMs = ringingForMs(),
                    configuredAction = configuredAction
                )
                when (outcome) {
                    PowerButtonDecision.Outcome.IGNORE -> {
                        AppLogger.i(TAG, "SCREEN_OFF ignored (wakelockHeld=$wakeLockHeld, screenOn=${screenWasOnForMs}ms) — NOT counted as button press")
                        return
                    }
                    PowerButtonDecision.Outcome.SNOOZE_INSTEAD_OF_DISMISS -> {
                        AppLogger.i(TAG, "SCREEN_OFF after ${sinceStart}ms, ringing=${ringingForMs()}ms < 10s → SNOOZE instead of dismiss (too fast to be intentional)")
                        onPowerButtonDecision?.invoke(outcome) ?: onPowerButton()
                        return
                    }
                    PowerButtonDecision.Outcome.POWER_BUTTON -> {
                        AppLogger.i(TAG, "Power button detected (SCREEN_OFF after ${sinceStart}ms, wakelockHeld=$wakeLockHeld)")
                        onPowerButtonDecision?.invoke(outcome) ?: onPowerButton()
                    }
                }
            }
        }
        // N-g: also register SCREEN_ON to keep screenOnAt current
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcast; NOT_EXPORTED is allowed for protected broadcasts
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(r, filter)
        }
        receiver = r
        AppLogger.i(TAG, "Started")
    }

    /** Read from alarm_settings (DP) — "dismiss" (default) or "snooze". */
    private fun readConfiguredAction(): String {
        return try {
            io.github.calendaralarm.data.DeviceProtectedStorage.prefs(
                context, io.github.calendaralarm.CalendarAlarmApp.PREFS_ALARM_SETTINGS
            ).getString(
                io.github.calendaralarm.CalendarAlarmApp.PREF_HARDWARE_BUTTON_ACTION,
                io.github.calendaralarm.CalendarAlarmApp.HARDWARE_ACTION_DISMISS
            ) ?: io.github.calendaralarm.CalendarAlarmApp.HARDWARE_ACTION_DISMISS
        } catch (e: Exception) {
            io.github.calendaralarm.CalendarAlarmApp.HARDWARE_ACTION_DISMISS
        }
    }

    @Synchronized
    fun stop() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        releaseScreenLock()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun acquireScreenLock() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            // SCREEN_BRIGHT is deprecated but still functional; prevents screen timeout
            // while ringing so that SCREEN_OFF only comes from the power button.
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CalendarAlarm:RingingScreen"
            ).apply { acquire(SCREEN_LOCK_TIMEOUT_MS) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Screen wake lock failed: ${e.message}")
        }
    }

    private fun releaseScreenLock() {
        try {
            screenLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            screenLock = null
        }
    }

    companion object {
        private const val GRACE_MS = 1_500L
        private const val SCREEN_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
