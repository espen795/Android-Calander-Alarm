package io.github.calendaralarm.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.receiver.OwnedAlarmReceiver
import io.github.calendaralarm.ui.AlarmRingingActivity

private const val TAG = "AlarmForegroundService"

/**
 * v2.5.7 Story 1: Foreground service that plays the alarm sound.
 *
 * Problem: Samsung One UI's aggressive battery optimization kills the app process
 * before MediaPlayer can start. The alarm fires in OwnedAlarmReceiver (BroadcastReceiver),
 * a background component that Samsung can kill.
 *
 * Solution: A foreground service with type mediaPlayback (Samsung respects this).
 * The service holds:
 * - MediaPlayer with USAGE_ALARM audio attributes (not killable)
 * - Vibrator with continuous pattern
 * - Partial WakeLock (30 min timeout) so the CPU stays awake
 * - Audio focus with AUDIOFOCUS_GAIN_TRANSIENT
 * - START_STICKY so Android restarts the service if it gets killed
 *
 * Samsung S24 / Android 15 compatibiliteit:
 * - foregroundServiceType=mediaPlayback — Samsung One UI respecteert dit type
 * - FOREGROUND_SERVICE_MEDIA_PLAYBACK permission (Android 14+ verplicht)
 * - startForeground() met FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK op Android 10+
 *
 * Manifest registratie:
 * <service
 *     android:name=".service.AlarmForegroundService"
 *     android:exported="false"
 *     android:foregroundServiceType="mediaPlayback" />
 */
class AlarmForegroundService : Service() {

    companion object {
        const val ACTION_START_ALARM = "io.github.calendaralarm.action.START_ALARM_SERVICE"
        const val ACTION_STOP_ALARM = "io.github.calendaralarm.action.STOP_ALARM_SERVICE"
        const val ACTION_START_SILENT_ALARM = "io.github.calendaralarm.action.START_SILENT_ALARM_SERVICE"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_TIME_LABEL = "extra_time_label"
        const val EXTRA_ALARM_LABEL = "extra_alarm_label"
        const val EXTRA_SILENT = "extra_silent"  // v2.6.11: Silent mode — no sound, only vibration + fullscreen

        // v2.5.7: Aparte notification ID voor alarm service
        private const val NOTIFICATION_ID = CalendarAlarmApp.NOTIFICATION_ID_ALARM_SERVICE

        // v2.5.7 PM fix CRIT-02: Persist alarm state for START_STICKY recovery after process kill
        private const val PREFS_NAME = "alarm_service_state"
        private const val KEY_ACTIVE_ALARM_ID = "active_alarm_id"
        private const val KEY_TIME_LABEL = "time_label"
        private const val KEY_ALARM_LABEL = "alarm_label"

        // WakeLock timeout: 30 minutes (enough for one alarm cycle)
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /**
         * Start the alarm foreground service.
         *
         * @param context Application context
         * @param alarmId ID of the alarm (for snooze/dismiss broadcasts)
         * @param timeLabel Time label (e.g. "07:30") for the notification
         * @param alarmLabel Label of the alarm for the notification
         */
        fun start(context: Context, alarmId: Int, timeLabel: String, alarmLabel: String) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_START_ALARM
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_TIME_LABEL, timeLabel)
                putExtra(EXTRA_ALARM_LABEL, alarmLabel)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.i(TAG, "AlarmForegroundService start requested (alarmId=$alarmId, time=$timeLabel)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start AlarmForegroundService: ${e.message}")
            }
        }

        /**
         * v2.6.11: Start the alarm foreground service in SILENT mode.
         * Shows fullscreen activity + vibration, but NO sound.
         * For testing the alarm without annoying sound.
         */
        fun startSilent(context: Context, alarmId: Int, timeLabel: String, alarmLabel: String) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_START_SILENT_ALARM
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_TIME_LABEL, timeLabel)
                putExtra(EXTRA_ALARM_LABEL, alarmLabel)
                putExtra(EXTRA_SILENT, true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.i(TAG, "AlarmForegroundService SILENT start requested (alarmId=$alarmId, time=$timeLabel)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start AlarmForegroundService (silent): ${e.message}")
            }
        }

        /**
         * Stop the alarm foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            try {
                context.stopService(intent)
                AppLogger.i(TAG, "AlarmForegroundService stop requested")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to stop AlarmForegroundService: ${e.message}")
            }
        }
    }

    // --- State ---
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var vibrator: Vibrator? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var hasAudioFocus = false
    private val handler = Handler(Looper.getMainLooper())

    // Current alarm info (for restart after kill)
    @Volatile private var currentAlarmId: Int = -1
    @Volatile private var currentTimeLabel: String = ""
    @Volatile private var currentAlarmLabel: String = ""

    // v2.6.0: Configurable options (global, can be overridden per alarm)
    private var volumeStartLevel: Float = 0.3f // Default 30%
    private var volumeBuildUpDurationMs: Long = 30000 // 30 seconds build-up
    private var isVibrationEnabled: Boolean = true
    private var vibrationPattern: LongArray = longArrayOf(0, 1000, 500) // short-long-short
    private var alarmSoundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    // v2.6.11: Silent mode — no sound, only vibration + fullscreen
    @Volatile private var isSilentMode: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM -> {
                currentAlarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                currentTimeLabel = intent.getStringExtra(EXTRA_TIME_LABEL) ?: ""
                currentAlarmLabel = intent.getStringExtra(EXTRA_ALARM_LABEL) ?: ""
                isSilentMode = false
                startAlarm()
                AppLogger.i(TAG, "AlarmForegroundService started (alarmId=$currentAlarmId, time=$currentTimeLabel)")
            }
            ACTION_START_SILENT_ALARM -> {
                // v2.6.11: Silent mode — test alarm without sound
                currentAlarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                currentTimeLabel = intent.getStringExtra(EXTRA_TIME_LABEL) ?: ""
                currentAlarmLabel = intent.getStringExtra(EXTRA_ALARM_LABEL) ?: ""
                isSilentMode = intent.getBooleanExtra(EXTRA_SILENT, true)
                AppLogger.i(TAG, "AlarmForegroundService SILENT started (alarmId=$currentAlarmId, time=$currentTimeLabel)")
                startAlarm()
            }
            ACTION_STOP_ALARM -> {
                stopAlarm()
                AppLogger.i(TAG, "AlarmForegroundService stopping via action")
            }
            else -> {
                // v2.5.7 PM fix CRIT-02: Service restarted after kill by system — restore state from prefs
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedId = prefs.getInt(KEY_ACTIVE_ALARM_ID, -1)
                if (savedId != -1) {
                    currentAlarmId = savedId
                    currentTimeLabel = prefs.getString(KEY_TIME_LABEL, "") ?: ""
                    currentAlarmLabel = prefs.getString(KEY_ALARM_LABEL, "") ?: ""
                    AppLogger.i(TAG, "AlarmForegroundService restored after kill (alarmId=$currentAlarmId)")
                    startAlarm()
                } else {
                    AppLogger.i(TAG, "AlarmForegroundService: no active alarm, stopping")
                    stopSelf()
                }
            }
        }
        return START_STICKY // Restart service if system kills it
    }

    override fun onDestroy() {
        stopAlarm()
        AppLogger.i(TAG, "AlarmForegroundService destroyed")
        super.onDestroy()
    }

    /**
     * v2.6.0: Load configurable options from SharedPreferences.
     */
    private fun loadAlarmSettings() {
        val prefs = getSharedPreferences(CalendarAlarmApp.PREFS_ALARM_SETTINGS, Context.MODE_PRIVATE)
        volumeStartLevel = prefs.getFloat(CalendarAlarmApp.PREF_VOLUME_START_LEVEL, 0.3f)
        volumeBuildUpDurationMs = prefs.getLong(CalendarAlarmApp.PREF_VOLUME_BUILD_UP_DURATION, 30000)
        isVibrationEnabled = prefs.getBoolean(CalendarAlarmApp.PREF_VIBRATION_ENABLED, true)

        // Vibration pattern (default: 0,1000,500 = wait-long-short)
        val patternString = prefs.getString(CalendarAlarmApp.PREF_VIBRATION_PATTERN, "0,1000,500")
        vibrationPattern = patternString?.split(",")?.map { it.trim().toLong() }?.toLongArray()
            ?: longArrayOf(0, 1000, 500)

        // Alarm sound
        val soundUriString = prefs.getString(CalendarAlarmApp.PREF_ALARM_SOUND_URI, null)
        alarmSoundUri = if (soundUriString != null && soundUriString.isNotEmpty()) {
            Uri.parse(soundUriString)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
    }

    /**
     * v2.6.7: NO activity launch from service!
     * The RECEIVER launches the activity (like v2.5.13).
     * Service is ONLY for sound/vibration (Samsung OOM protection).
     */

    /**
     * Start the alarm: foreground notification, wake lock, audio focus, MediaPlayer, vibrator.
     * v2.6.11: In silent mode NO sound is started, only vibration + fullscreen.
     */
    private fun startAlarm() {
        // v2.6.0: Load configurable options
        loadAlarmSettings()

        // v2.5.7 PM fix CRIT-02: Persist state for START_STICKY recovery
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_ACTIVE_ALARM_ID, currentAlarmId)
            putString(KEY_TIME_LABEL, currentTimeLabel)
            putString(KEY_ALARM_LABEL, currentAlarmLabel)
            apply()
        }

        // 1. Start as foreground service with notification (must happen within 5 seconds of startForegroundService)
        startForegroundWithNotification()

        // 2. Acquire partial wake lock (30 min timeout)
        acquireWakeLock()

        // v2.6.11: In silent mode NO audio focus and NO MediaPlayer
        if (!isSilentMode) {
            // 3. Request audio focus
            requestAudioFocus()

            // 4. Start MediaPlayer (async prepare)
            startMediaPlayer()
        } else {
            AppLogger.i(TAG, "SILENT MODE: Skipping audio focus and MediaPlayer (no sound)")
        }

        // 5. Start vibrator (always, also in silent mode)
        if (isVibrationEnabled) {
            startVibration()
        }
    }

    /**
     * Stop everything: MediaPlayer, vibrator, wake lock, audio focus, foreground.
     */
    private fun stopAlarm() {
        // v2.5.7 PM fix CRIT-02: Clear persisted state
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE_ALARM_ID)
            .apply()

        stopMediaPlayer()
        stopVibration()
        releaseWakeLock()
        abandonAudioFocus()
        stopForegroundService()
        currentAlarmId = -1
    }

    // --- Foreground notification ---

    private fun startForegroundWithNotification() {
        val notification = buildNotification()

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
     * Build the ongoing notification for the alarm service.
     * Shows "⏰ Alarm running: {timeLabel}" with snooze/dismiss actions.
     *
     * v2.5.13 Story 1: Full-screen intent → AlarmRingingActivity for lock screen.
     */
    private fun buildNotification(): Notification {
        // v2.5.13: Full-screen intent → AlarmRingingActivity (was MainActivity)
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            currentAlarmId + 300000, // Unique request code
            Intent(this, AlarmRingingActivity::class.java).apply {
                putExtra(AlarmRepository.EXTRA_ALARM_ID, currentAlarmId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tap → also open AlarmRingingActivity (same as full-screen)
        val contentIntent = fullScreenIntent

        // Snooze action → broadcast to OwnedAlarmReceiver with ACTION_SNOOZE
        val snoozeIntent = Intent(this, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_SNOOZE
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, currentAlarmId)
        }
        val snoozePi = PendingIntent.getBroadcast(
            this,
            currentAlarmId + 100000,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss action → broadcast to OwnedAlarmReceiver with ACTION_DISMISS
        val dismissIntent = Intent(this, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_DISMISS
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, currentAlarmId)
        }
        val dismissPi = PendingIntent.getBroadcast(
            this,
            currentAlarmId + 200000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (currentTimeLabel.isNotEmpty()) {
            "⏰ Alarm running: $currentTimeLabel"
        } else {
            "⏰ Alarm running"
        }

        val text = if (currentAlarmLabel.isNotEmpty()) {
            currentAlarmLabel
        } else {
            "Alarm active"
        }

        return NotificationCompat.Builder(this, CalendarAlarmApp.CHANNEL_ALARMS_RINGING)  // v2.6.9: New channel with IMPORTANCE_HIGH
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            // v2.6.9: setFullScreenIntent RESTORED — this is THE fullscreen trigger when the alarm fires
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze", snoozePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPi)
            .setAutoCancel(false)
            .build()
    }

    private fun stopForegroundService() {
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

    // --- Wake Lock ---

    private fun acquireWakeLock() {
        try {
            releaseWakeLock() // Release first if we already have one
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CalendarAlarm:AlarmForegroundServiceWakeLock"
            )
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = wl
            AppLogger.i(TAG, "WakeLock acquired (timeout=${WAKE_LOCK_TIMEOUT_MS}ms)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            AppLogger.i(TAG, "WakeLock released")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }

    // --- Audio Focus ---
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am

            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // v2.5.7 PM fix MAJOR-01: Store AudioFocusRequest for proper abandonment
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        // v2.5.7 PM fix: Stop alarm if focus is permanently lost
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            AppLogger.w(TAG, "Audio focus lost permanently — stopping alarm")
                            stopAlarm()
                        }
                    }
                    .build()
                audioFocusRequest = focusRequest // Store for abandonment
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }

            hasAudioFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            AppLogger.i(TAG, "Audio focus requested: granted=$hasAudioFocus")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to request audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (hasAudioFocus) {
                val am = audioManager ?: return
                // v2.5.7 PM fix MAJOR-01: Use correct abandonment method for Android O+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                    audioFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
                hasAudioFocus = false
                AppLogger.i(TAG, "Audio focus abandoned")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    // --- MediaPlayer ---

    /**
     * v2.6.0: Start volume build-up (gradually increasing the volume).
     */
    private fun startVolumeBuildUp() {
        if (volumeBuildUpDurationMs <= 0) return

        val startVolume = volumeStartLevel
        val endVolume = 1.0f
        val steps = 30 // Number of steps for build-up
        val stepDelay = volumeBuildUpDurationMs / steps

        handler.post(object : Runnable {
            private var currentStep = 0

            override fun run() {
                if (currentStep >= steps) return

                val progress = currentStep.toFloat() / steps
                val volume = startVolume + (endVolume - startVolume) * progress
                mediaPlayer?.setVolume(volume, volume)

                currentStep++
                if (currentStep < steps) {
                    handler.postDelayed(this, stepDelay)
                }
            }
        })
    }

    private fun startMediaPlayer() {
        try {
            stopMediaPlayer() // Stop first if we already have one

            val player = MediaPlayer().apply {
                setDataSource(this@AlarmForegroundService, alarmSoundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                // v2.6.0: Start with configured start volume
                setVolume(volumeStartLevel, volumeStartLevel)

                // v2.5.7: Use prepareAsync() with listener (non-blocking)
                setOnPreparedListener { mp ->
                    try {
                        mp.start()
                        AppLogger.i(TAG, "MediaPlayer prepared and started (startVolume=$volumeStartLevel)")
                        // v2.6.0: Start volume build-up after start
                        startVolumeBuildUp()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to start MediaPlayer after prepare: ${e.message}")
                    }
                }

                // v2.5.7: Error listener returns true (error consumed)
                setOnErrorListener { _, what, extra ->
                    AppLogger.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    true // Error consumed
                }

                prepareAsync()
            }

            mediaPlayer = player
            AppLogger.i(TAG, "MediaPlayer created with prepareAsync() (sound=$alarmSoundUri)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start MediaPlayer: ${e.message}")
        }
    }

    private fun stopMediaPlayer() {
        try {
            mediaPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        mp.stop()
                    }
                } catch (_: Exception) {}
                try {
                    mp.release()
                } catch (_: Exception) {}
            }
            mediaPlayer = null
            AppLogger.i(TAG, "MediaPlayer stopped and released")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping MediaPlayer: ${e.message}")
        }
    }

    // --- Vibrator ---

    private fun startVibration() {
        try {
            stopVibration() // Stop first if we already have one

            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // v2.6.0: Use configurable vibration pattern
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(vibrationPattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(vibrationPattern, 0)
            }

            vibrator = vib
            AppLogger.i(TAG, "Vibration started (pattern: ${vibrationPattern.joinToString()}) repeat=0")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start vibration: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
            AppLogger.i(TAG, "Vibration stopped")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping vibration: ${e.message}")
        }
    }
}
