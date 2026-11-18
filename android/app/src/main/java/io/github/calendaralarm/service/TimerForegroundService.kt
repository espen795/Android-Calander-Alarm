package io.github.calendaralarm.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.calendaralarm.MainActivity
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerStateStore
import io.github.calendaralarm.receiver.TimerActionReceiver
import io.github.calendaralarm.ui.TimerRingingActivity

private const val TAG = "TimerForegroundService"

/**
 * v2.5.6 Story 6: Foreground service that runs as long as the timer is running.
 *
 * Goal: Keeps the app process alive so Samsung's aggressive OOM killer
 * cannot kill the timer. Shows an ongoing notification with a Stop action.
 *
 * Samsung S24 / Android 15 compatibiliteit:
 * - foregroundServiceType=mediaPlayback — Samsung One UI respecteert dit type
 * - FOREGROUND_SERVICE_MEDIA_PLAYBACK permission (Android 14+ verplicht)
 * - startForeground() met FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK op Android 10+
 *
 * Manifest registratie:
 * <service
 *     android:name=".service.TimerForegroundService"
 *     android:exported="false"
 *     android:foregroundServiceType="mediaPlayback" />
 */
class TimerForegroundService : Service() {

    companion object {
        const val ACTION_START_TIMER = "io.github.calendaralarm.action.START_TIMER_SERVICE"
        const val ACTION_STOP_TIMER = "io.github.calendaralarm.action.STOP_TIMER_SERVICE"
        const val EXTRA_REMAINING_MS = "extra_remaining_ms"

        // v2.5.6 PM fix C-02: Separate notification ID for the service (was NOTIFICATION_ID_TIMER = 5001, conflict with UI + fired)
        private const val NOTIFICATION_ID = CalendarAlarmApp.NOTIFICATION_ID_TIMER_SERVICE

        /**
         * Start the foreground service (when it is not already running).
         */
        fun start(context: Context, remainingMs: Long) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_START_TIMER
                putExtra(EXTRA_REMAINING_MS, remainingMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.i(TAG, "TimerForegroundService start requested")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start TimerForegroundService: ${e.message}")
            }
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_STOP_TIMER
            }
            try {
                context.stopService(intent)
                AppLogger.i(TAG, "TimerForegroundService stop requested")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to stop TimerForegroundService: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // v2.6.0: Live update handler
    private val handler = Handler(Looper.getMainLooper())
    private var startTime: Long = 0L
    private var remainingMs: Long = 0L
    private var timerComplete = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (timerComplete) return
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val remainingMs = intent.getLongExtra(EXTRA_REMAINING_MS, 0L)
                this.remainingMs = remainingMs
                this.startTime = SystemClock.elapsedRealtime()
                this.timerComplete = false
                startForegroundWithNotification(remainingMs)
                handler.post(updateRunnable)
                AppLogger.i(TAG, "TimerForegroundService started (remaining=${remainingMs}ms)")
            }
            ACTION_STOP_TIMER -> {
                stopForegroundService()
                AppLogger.i(TAG, "TimerForegroundService stopping")
            }
            else -> {
                // Service restarted (e.g. after kill by system) — check whether there is an active timer
                val stateStore = TimerStateStore(this)
                val remaining = stateStore.getRemainingMs()
                if (remaining != null && remaining > 0) {
                    this.remainingMs = remaining
                    this.startTime = SystemClock.elapsedRealtime()
                    this.timerComplete = false
                    startForegroundWithNotification(remaining)
                    handler.post(updateRunnable)
                    AppLogger.i(TAG, "TimerForegroundService restarted (remaining=${remaining}ms)")
                } else {
                    stopSelf()
                    AppLogger.i(TAG, "TimerForegroundService: no active timer, stopping")
                }
            }
        }
        return START_STICKY // Restart service if system kills it
    }

    override fun onDestroy() {
        stopForegroundService()
        AppLogger.i(TAG, "TimerForegroundService destroyed")
        super.onDestroy()
    }

    /**
     * Start as foreground service with an ongoing notification.
     */
    private fun startForegroundWithNotification(remainingMs: Long) {
        val notification = buildNotification(remainingMs)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (Samsung S24 = Android 15): mediaPlayback type
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AppLogger.i(TAG, "Foreground notification shown (type=mediaPlayback)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start foreground: ${e.message}")
            // Fallback: stop service if foreground fails
            stopSelf()
        }
    }

    /**
     * v2.6.4: Update the notification every second with the remaining time.
     * NO activity launch here — the TimerFireReceiver does that already.
     * Double launch caused a crash.
     */
    private fun updateNotification() {
        val elapsedMs = SystemClock.elapsedRealtime() - startTime
        val newRemainingMs = remainingMs - elapsedMs

        if (newRemainingMs <= 0) {
            // Timer expired — only stop, NO activity launch
            // The TimerFireReceiver launches TimerRingingActivity
            timerComplete = true
            handler.removeCallbacks(updateRunnable)
            stopForegroundService()
            AppLogger.i(TAG, "Timer completed — service stopping (activity handled by TimerFireReceiver)")
            return
        }

        // Update the notification with the new time
        val notification = buildNotification(newRemainingMs)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Build the ongoing notification for the timer service with live countdown.
     */
    private fun buildNotification(remainingMs: Long): Notification {
        // Tap → open MainActivity
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action → broadcast to TimerActionReceiver
        val stopIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, TimerActionReceiver::class.java).apply {
                action = TimerActionReceiver.ACTION_STOP_TIMER
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Format remaining time
        val remainingText = formatDuration(remainingMs)

        return NotificationCompat.Builder(this, CalendarAlarmApp.CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏱️ Timer running")
            .setContentText("$remainingText remaining")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setAutoCancel(false)
            .build()
    }

    /**
     * Stop the foreground state and cancel the notification.
     */
    private fun stopForegroundService() {
        handler.removeCallbacks(updateRunnable)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Cancel notification (in case stopForeground doesn't do it)
            try {
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
            stopSelf()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping foreground: ${e.message}")
        }
    }

    /**
     * Format milliseconds as a human-readable duration.
     */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
