package io.github.calendaralarm.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerAlarmHelper
import io.github.calendaralarm.receiver.TimerActionReceiver
import io.github.calendaralarm.ui.TimerRingingActivity

private const val TAG = "TimerRingService"

/**
 * v2.7.0: Sole owner of the "timer finished" ringing.
 *
 * Previously TimerFireReceiver and TimerScreen (via the ViewModel loop) each started
 * the sound and each a TimerRingingActivity. The second launch (CLEAR_TOP without
 * SINGLE_TOP) destroyed the first activity, whose onDestroy stopped the sound →
 * screen without sound. Moreover the sound ran in a background process without a
 * foreground service (Samsung freezes that).
 * Now:
 * - Started via [ring] by TimerFireReceiver.fire() (exactly once, claimed in TimerStateStore)
 * - Foreground service (mediaPlayback) → process stays alive, sound via TimerAlarmHelper
 * - Full-screen intent on a HIGH channel → TimerRingingActivity, also on the lock screen
 *   (same proven pattern as AlarmForegroundService)
 * - Stop via [stop]: button in activity, notification action, power button or volume button
 * - [ringing] StateFlow: activity closes itself once ringing stops
 */
class TimerRingService : Service() {

    companion object {
        private const val ACTION_RING = "io.github.calendaralarm.action.TIMER_RING"
        private const val NOTIFICATION_ID = CalendarAlarmApp.NOTIFICATION_ID_TIMER_RINGING
        private const val PREFS = "timer_ring_state"
        private const val KEY_RINGING = "ringing"
        private const val REQUEST_ACTIVITY = 5104
        private const val REQUEST_STOP = 5105

        private val _ringing = MutableStateFlow(false)
        val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

        fun ring(context: Context) {
            val intent = Intent(context, TimerRingService::class.java).apply { action = ACTION_RING }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.i(TAG, "Ring requested")
            } catch (e: Exception) {
                // Last resort: sound without service (can be frozen by the OEM)
                AppLogger.e(TAG, "startForegroundService failed: ${e.message} — fallback direct sound")
                TimerAlarmHelper.getInstance(context).startAlarm()
                _ringing.value = true
            }
        }

        /** Stop the ringing. Safe to always call. */
        fun stop(context: Context) {
            AppLogger.i(TAG, "Stop requested")
            TimerAlarmHelper.stopSharedAlarm()
            _ringing.value = false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RINGING, false).commit()
            try {
                context.stopService(Intent(context, TimerRingService::class.java))
            } catch (_: Exception) {}
            NotificationManagerCompat.from(context).apply {
                cancel(NOTIFICATION_ID)
                cancel(CalendarAlarmApp.NOTIFICATION_ID_TIMER_FIRED) // legacy (≤ v2.6.13)
            }
        }
    }

    private var powerButtonWatcher: PowerButtonWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shouldRing = when (intent?.action) {
            ACTION_RING -> true
            // START_STICKY restarts after kill: only continue if we were ringing
            else -> prefs.getBoolean(KEY_RINGING, false)
        }
        if (!shouldRing) {
            AppLogger.i(TAG, "No active ringing — service stops")
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground must happen within 5s of startForegroundService
        if (!startForegroundWithNotification()) {
            // Without foreground still try sound — better than silence
            AppLogger.w(TAG, "Foreground failed — sound without FGS")
        }
        prefs.edit().putBoolean(KEY_RINGING, true).commit()
        TimerAlarmHelper.getInstance(this).startAlarm()
        _ringing.value = true

        if (powerButtonWatcher == null) {
            // P1 review#8: named args — the new PowerButtonWatcher has extra
            // parameters (onPowerButtonDecision, ringingForMs) with defaults.
            powerButtonWatcher = PowerButtonWatcher(
                context = this,
                onPowerButton = {
                    AppLogger.i(TAG, "Power button → stop timer")
                    stop(applicationContext)
                }
            ).also { it.start() }
        }
        AppLogger.i(TAG, "Timer ringing (sound + vibration + FSI)")
        return START_STICKY
    }

    override fun onDestroy() {
        powerButtonWatcher?.stop()
        powerButtonWatcher = null
        TimerAlarmHelper.stopSharedAlarm()
        _ringing.value = false
        AppLogger.i(TAG, "Destroyed")
        super.onDestroy()
    }

    private fun startForegroundWithNotification(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "startForeground failed: ${e.message}")
            false
        }
    }

    private fun buildNotification(): Notification {
        val activityPi = PendingIntent.getActivity(
            this,
            REQUEST_ACTIVITY,
            Intent(this, TimerRingingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getBroadcast(
            this,
            REQUEST_STOP,
            Intent(this, TimerActionReceiver::class.java).apply {
                action = TimerActionReceiver.ACTION_STOP_TIMER
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CalendarAlarmApp.CHANNEL_TIMER_RINGING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ Timer finished")
            .setContentText("Tap to open · Stop to turn off")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(activityPi)
            .setFullScreenIntent(activityPi, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setAutoCancel(false)
            .build()
    }

}
