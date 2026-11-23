package io.github.calendaralarm.service

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * B5 (review): fallback chain for the alarm sound.
 *
 * Problem: AlarmForegroundService did setDataSource(alarmSoundUri) without
 * fallback. A custom sound (content://media/...) can fail before the first unlock
 * (Direct Boot) → then ONLY vibration sounded.
 *
 * Chain (pure function, unit-testable):
 *   1. configured alarmSoundUri (when set)
 *   2. Settings.System.DEFAULT_ALARM_ALERT_URI (system)
 *   3. bundled res/raw/fallback_alarm.wav (always available, also before unlock)
 */
object AlarmSoundFallback {

    /** Returns the ordered list of sound URIs — playback tries them in this order. */
    fun fallbackChain(configuredSoundUri: Uri?): List<Uri> {
        val chain = mutableListOf<Uri>()
        if (configuredSoundUri != null) chain.add(configuredSoundUri)
        val systemDefault = try {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        } catch (e: Exception) { null }
        if (systemDefault != null && systemDefault !in chain) chain.add(systemDefault)
        // res/raw fallback — always present in the APK
        chain.add(Uri.parse("android.resource://io.github.calendaralarm/${io.github.calendaralarm.R.raw.fallback_alarm}"))
        return chain
    }

    /** URI of the bundled fallback sound (raw resource). */
    fun bundledFallbackUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${io.github.calendaralarm.R.raw.fallback_alarm}")
}
