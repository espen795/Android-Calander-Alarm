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
 * - Audio focus met AUDIOFOCUS_GAIN_TRANSIENT
 * - START_STICKY so Android restarts the service if it gets killed
 *
 * Samsung S24 / Android 15 compatibility:
 * - foregroundServiceType=mediaPlayback — Samsung One UI respecteert dit type
 * - FOREGROUND_SERVICE_MEDIA_PLAYBACK permission (Android 14+ verplicht)
 * - startForeground() met FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK op Android 10+
 *
 * Manifest registration:
 * <service
 *     android:name=".service.AlarmForegroundService"
 *     android:exported="false"
 *     android:foregroundServiceType="mediaPlayback" />
 */
class AlarmForegroundService : Service() {

    companion object {
        const val ACTION_START_ALARM = "io.github.calendaralarm.action.START_ALARM_SERVICE"
        const val ACTION_STOP_ALARM = "io.github.calendaralarm.action.STOP_ALARM_SERVICE"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_TIME_LABEL = "extra_time_label"
        const val EXTRA_ALARM_LABEL = "extra_alarm_label"
        const val EXTRA_SILENT = "extra_silent"  // v2.6.11: Silent mode — no sound, only vibration + fullscreen

        // v2.5.7: Separate notification ID for alarm service
        // P1 review#17: from the central register (4100 — same number, now claimed in the 4000 block)
        private const val NOTIFICATION_ID = NotificationIds.ALARM_SERVICE

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
         * @param timeLabel Time label (e.g. "07:30") for in the notification
         * @param alarmLabel Label of the alarm for in the notification
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

    // Re-review: true between startAlarm() and stopAlarm(); guard for posted fallback runnables
    @Volatile private var ringingActive: Boolean = false

    // v2.7.0: power button while ringing = dismiss/snooze (KEYCODE_POWER does not reach the app)
    private var powerButtonWatcher: PowerButtonWatcher? = null
    // P1 review#8: start time of the ringing (for the <10s snooze guard)
    @Volatile private var ringingStartedAt = 0L

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
            ACTION_STOP_ALARM -> {
                stopAlarm()
                AppLogger.i(TAG, "AlarmForegroundService stopping via action")
            }
            else -> {
                // v2.5.7 PM fix CRIT-02: Service restart after kill by system — restore state from prefs
                val prefs = io.github.calendaralarm.data.DeviceProtectedStorage.prefs(this, PREFS_NAME)  // P1 #1: DP (service draait vóór unlock)
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
        return START_STICKY // Restart service if the system kills it
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
        val prefs = io.github.calendaralarm.data.DeviceProtectedStorage.prefs(this, CalendarAlarmApp.PREFS_ALARM_SETTINGS)  // P1 #1: DP — non-secret alarm settings
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
        ringingActive = true
        // v2.6.0: Laad instelbare opties
        loadAlarmSettings()

        // v2.5.7 PM fix CRIT-02: Persist state for START_STICKY recovery
        // P1 #1: DP storage (service draait vóór unlock)
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(this, PREFS_NAME).edit().apply {
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

        // 6. v2.7.0: power button → snooze/dismiss (setting), also without visible activity
        // P1 review#8: pass how long we have been ringing + decision callback that on
        // SNOOZE_INSTEAD_OF_DISMISS sends snooze instead of dismiss (<10s ringing).
        if (powerButtonWatcher == null) {
            ringingStartedAt = android.os.SystemClock.elapsedRealtime()
            powerButtonWatcher = PowerButtonWatcher(
                context = this,
                onPowerButton = { onHardwareButton("power") },
                onPowerButtonDecision = { outcome ->
                    if (outcome == PowerButtonDecision.Outcome.SNOOZE_INSTEAD_OF_DISMISS) {
                        onHardwareButton("power-snooze")
                    } else {
                        onHardwareButton("power")
                    }
                },
                ringingForMs = { android.os.SystemClock.elapsedRealtime() - ringingStartedAt }
            ).also { it.start() }
        }
    }

    /**
     * v2.7.0: Physical button while ringing → same path as the buttons in the screen/the
     * notification (OwnedAlarmReceiver), so snooze/dismiss/re-arm logic stays in one place.
     */
    private fun onHardwareButton(source: String) {
        val alarmId = currentAlarmId
        if (alarmId == -1) {
            AppLogger.w(TAG, "Hardware button ($source) without alarmId — stopping service")
            stopAlarm()
            stopSelf()
            return
        }
        // P1 review#8: "power-snooze" = SCREEN_OFF within 10s of ringing with setting
        // "dismiss" → force SNOOZE (too fast to be intentional).
        val action = if (source == "power-snooze") {
            OwnedAlarmReceiver.ACTION_SNOOZE
        } else {
            OwnedAlarmReceiver.hardwareButtonAction(this, alarmId)
        }
        AppLogger.i(TAG, "Hardware button ($source) → $action (alarmId=$alarmId)")
        sendBroadcast(Intent(this, OwnedAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        })
    }

    /**
     * Stop everything: MediaPlayer, vibrator, wake lock, audio focus, foreground.
     */
    private fun stopAlarm() {
        // v2.5.7 PM fix CRIT-02: Clear persisted state
        // P1 #1: DP storage (service draait vóór unlock)
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(this, PREFS_NAME).edit()
            .remove(KEY_ACTIVE_ALARM_ID)
            .apply()

        // Re-review fix: discard posted fallback/volume runnables before we stop
        ringingActive = false
        handler.removeCallbacksAndMessages(null)
        powerButtonWatcher?.stop()
        powerButtonWatcher = null
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
     * Shows "⏰ Alarm ringing: {timeLabel}" with snooze/dismiss actions.
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
            "⏰ Alarm ringing: $currentTimeLabel"
        } else {
            "⏰ Alarm ringing"
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
            // Cancel notification (in case stopForeground does not do it)
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
     * v2.6.0: Start volume build-up (gradually building up the volume).
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

    // B5: index in the fallback chain — on error of the current URI we move on
    private var soundChainIndex = 0

    private fun startMediaPlayer() {
        stopMediaPlayer() // Stop first if we already have one
        soundChainIndex = 0
        startMediaPlayerAt(0)
    }

    private fun startMediaPlayerAt(index: Int) {
        // Re-review fix: a posted fallback runnable may not start sound anymore after dismiss/snooze
        if (!ringingActive || isSilentMode) return
        // B5 (review): fallback chain — own sound → system default alarm → bundled raw.
        // A content:// URI can fail before the first unlock; then we fall through.
        val chain = AlarmSoundFallback.fallbackChain(
            configuredSoundUri = run {
                val s = alarmSoundUri.toString()
                // if the configured URI equals the default, skip (duplicate in chain)
                if (s.contains("DEFAULT_ALARM_ALERT_URI") || s.isEmpty()) null else alarmSoundUri
            }
        )
        if (index >= chain.size) {
            AppLogger.e(TAG, "B5: all sound sources failed — vibration only (last resort)")
            return
        }
        val uri = chain[index]
        try {
            val player = MediaPlayer().apply {
                setDataSource(this@AlarmForegroundService, uri)
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
                        AppLogger.i(TAG, "MediaPlayer started (source ${index + 1}/${chain.size}: $uri)")
                        // v2.6.0: Start volume build-up after start
                        startVolumeBuildUp()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "B5: start() failed for $uri: ${e.message} — next source")
                        onSoundFailed(index)
                    }
                }

                // B5: on error → next source in the chain
                setOnErrorListener { _, what, extra ->
                    AppLogger.e(TAG, "B5: MediaPlayer error (what=$what, extra=$extra) for $uri — next source")
                    onSoundFailed(index)
                    true // Error consumed
                }

                prepareAsync()
            }

            mediaPlayer = player
            AppLogger.i(TAG, "MediaPlayer prepareAsync (source ${index + 1}/${chain.size}: $uri)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "B5: setDataSource/prepare failed for $uri: ${e.message} — next source")
            onSoundFailed(index)
        }
    }

    /** B5: try the next sound source; at end of chain → log ERROR (vibration remains). */
    private fun onSoundFailed(failedIndex: Int) {
        stopMediaPlayer()
        val next = failedIndex + 1
        soundChainIndex = next
        // post on main handler so we do not stay stuck inside the MediaPlayer callback
        handler.post { startMediaPlayerAt(next) }
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
