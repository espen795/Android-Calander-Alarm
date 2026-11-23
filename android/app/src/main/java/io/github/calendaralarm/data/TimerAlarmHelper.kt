package io.github.calendaralarm.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

private const val TAG = "TimerAlarmHelper"

/**
 * Helper class for timer alarm sound and vibration.
 *
 * Manages:
 * - Looping alarm sound via MediaPlayer with USAGE_ALARM audio attributes
 * - Continuous vibration pattern (repeating until stopped)
 * - WakeLock to keep screen/CPU awake while alarm is ringing
 * - Clean stop/release of resources
 *
 * v2.5.2: Created for Fix 2 — timer now rings like a real alarm clock.
 * v2.5.2 PM fix: Added WakeLock so alarm continues when screen turns off.
 * v2.5.3: Added companion object with shared instance for cross-component access
 *         (TimerRingingActivity, TimerActionReceiver can stop the alarm).
 */
class TimerAlarmHelper(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var isRinging = false

    companion object {
        @Volatile
        private var sharedInstance: TimerAlarmHelper? = null

        /**
         * v2.5.3: Get or create the shared instance.
         * Used by TimerRingingActivity and TimerActionReceiver to stop the alarm.
         */
        @Synchronized
        fun getInstance(context: Context): TimerAlarmHelper {
            return sharedInstance ?: TimerAlarmHelper(context.applicationContext).also {
                sharedInstance = it
            }
        }

        /**
         * v2.5.3: Stop the alarm on the shared instance (if any).
         * Safe to call from any component — no-op if no instance exists.
         */
        @Synchronized
        fun stopSharedAlarm() {
            sharedInstance?.stopAlarm()
            AppLogger.i(TAG, "stopSharedAlarm called")
        }

        /**
         * v2.5.3: Check if the shared alarm is ringing.
         */
        @Synchronized
        fun isSharedAlarmRinging(): Boolean {
            return sharedInstance?.isAlarmRinging() ?: false
        }

        /**
         * v2.5.3: Release the shared instance (call when app is done with timer).
         */
        @Synchronized
        fun releaseShared() {
            sharedInstance?.release()
            sharedInstance = null
        }
    }

    /**
     * Start the alarm: play looping ringtone + continuous vibration.
     */
    @Synchronized
    fun startAlarm() {
        if (isRinging) {
            AppLogger.i(TAG, "Alarm already ringing, ignoring start request")
            return
        }

        AppLogger.i(TAG, "Starting alarm: sound + vibration + wakelock")
        isRinging = true

        startWakeLock()
        startSound()
        startVibration()
    }

    /**
     * Stop the alarm: stop sound and vibration, release resources.
     *
     * v2.7.0: Always clean up, even when isRinging is already false. Previously a
     * MediaPlayer error set isRinging=false while vibration + wakelock kept running; stopAlarm()
     * was then a no-op and the vibration never stopped.
     */
    @Synchronized
    fun stopAlarm() {
        if (isRinging) AppLogger.i(TAG, "Stopping alarm")
        isRinging = false

        stopWakeLock()
        stopSound()
        stopVibration()
    }

    /**
     * Check if the alarm is currently ringing.
     */
    @Synchronized
    fun isAlarmRinging(): Boolean = isRinging

    // ── WakeLock ────────────────────────────────────────────────────

    /**
     * v2.5.2 PM fix: Acquire a partial wake lock so the alarm keeps ringing
     * even when the screen turns off. Released in stopWakeLock().
     */
    @SuppressLint("WakelockTimeout")
    private fun startWakeLock() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CalendarAlarm:TimerWakeLock"
            ).apply {
                acquire(30 * 60 * 1000L) // 30 min max — safety timeout
            }
            AppLogger.i(TAG, "WakeLock acquired (30min timeout)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun stopWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    AppLogger.i(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing WakeLock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    // ── Sound ───────────────────────────────────────────────────────

    private fun startSound(useFallback: Boolean = false) {
        try {
            // Default alarm ringtone; on a playback error fall back once to the ringtone
            val alarmUri = if (!useFallback) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true  // Loop like a real alarm clock
                setOnPreparedListener { mp ->
                    synchronized(this@TimerAlarmHelper) {
                        // Alarm may have been stopped while prepareAsync was still running
                        if (!isRinging || mediaPlayer !== mp) return@setOnPreparedListener
                        mp.start()
                    }
                    AppLogger.i(TAG, "Alarm sound started (looping, fallback=$useFallback)")
                }
                setOnErrorListener { mp, what, extra ->
                    AppLogger.e(TAG, "MediaPlayer error: what=$what, extra=$extra (fallback=$useFallback)")
                    synchronized(this@TimerAlarmHelper) {
                        try { mp.release() } catch (_: Exception) {}
                        if (mediaPlayer === mp) mediaPlayer = null
                        // v2.7.0: do NOT reset isRinging (vibration keeps running); try fallback sound
                        if (isRinging && !useFallback) startSound(useFallback = true)
                    }
                    true // Error handled — prevents crash
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start alarm sound: ${e.message}")
            mediaPlayer = null
        }
    }

    private fun stopSound() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping sound: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }

    // ── Vibration ───────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // Continuous alarm pattern: vibrate 1000ms, pause 500ms, repeat forever
            // This is more alarm-like than the old 3x500ms pattern
            val pattern = longArrayOf(0, 1000, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = repeat indefinitely
            } else {
                vibrator?.vibrate(pattern, 0) // 0 = repeat indefinitely
            }
            AppLogger.i(TAG, "Alarm vibration started (continuous)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start vibration: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            AppLogger.i(TAG, "Alarm vibration stopped")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping vibration: ${e.message}")
        } finally {
            vibrator = null
        }
    }

    /**
     * Clean up resources. Call from onCleared or when leaving the screen.
     */
    fun release() {
        stopAlarm()
    }
}
