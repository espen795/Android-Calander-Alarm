package io.github.calendaralarm.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.service.AlarmForegroundService
import io.github.calendaralarm.ui.AlarmRingingActivity

/**
 * Receives alarm fire events from AlarmManager and launches the full-screen
 * ringing activity. Also handles snooze/dismiss actions.
 *
 * v2.5.7 Story 1: Alarm sound is now played via AlarmForegroundService
 * (foreground service with mediaPlayback type) so Samsung's OOM killer
 * cannot kill the process before MediaPlayer starts.
 *
 * NC-01 fix: Plays an actual ringtone via MediaPlayer with audio focus (nu in service).
 * NC-02 fix: Does NOT delete the fired alarm from the store — snooze/dismiss
 *   can still reference it. Removed only on dismiss or after snooze replaces it.
 *
 * F6: Snooze duration is read from SettingsRepository (configurable: 5/10/15/30 min).
 */
class OwnedAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OwnedAlarmReceiver"
        const val ACTION_SNOOZE = "io.github.calendaralarm.action.SNOOZE"
        const val ACTION_DISMISS = "io.github.calendaralarm.action.DISMISS"
        const val EXTRA_ALARM_ID = AlarmRepository.EXTRA_ALARM_ID
        const val DEFAULT_SNOOZE_MINUTES = 5L  // F6: fallback if settings unavailable

        /**
         * F6: Get the configured snooze duration in minutes from settings.
         */
        fun getSnoozeMinutes(context: Context): Long {
            return try {
                io.github.calendaralarm.CalendarAlarmApp.settingsRepository.getSnoozeMinutes().toLong()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to read snooze minutes from settings, using default")
                DEFAULT_SNOOZE_MINUTES
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        if (alarmId == -1) {
            AppLogger.w(TAG, "Received intent without alarm id")
            return
        }

        AppLogger.i(TAG, "Received intent: action=${intent.action}, alarmId=$alarmId")
        when (intent.action) {
            ACTION_SNOOZE -> handleSnooze(context, alarmId)
            ACTION_DISMISS -> handleDismiss(context, alarmId)
            else -> handleAlarmFired(context, alarmId)
        }
    }

    private fun handleAlarmFired(context: Context, alarmId: Int) {
        AppLogger.i(TAG, "v2.6.10: handleAlarmFired() called — alarmId=$alarmId")
        android.widget.Toast.makeText(context, "⏰ ALARM FIRING! (id=$alarmId)", android.widget.Toast.LENGTH_LONG).show()
        
        val repo = AlarmRepository(context)
        val alarm = repo.store.get(alarmId)
        if (alarm == null) {
            AppLogger.w(TAG, "v2.6.10: Alarm $alarmId not found in store")
            android.widget.Toast.makeText(context, "❌ Alarm not found in store!", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        AppLogger.i(TAG, "v2.6.10: Alarm found: ${alarm.timeLabel} - ${alarm.label}")

        // Wake lock so device stays awake (backup — service also has a wake lock)
        // v2.6.9 FIX: Remove ACQUIRE_CAUSES_WAKEUP and ON_AFTER_RELEASE — incompatible with PARTIAL_WAKE_LOCK
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CalendarAlarm:AlarmWakeLock"
        )
        wakeLock.acquire(30_000) // 30 seconds

        // v2.6.10 FIX (GPT Sol): ONE FSI notification via foreground service
        // Removed: showAlarmRingingNotification() — this was a SECOND FSI notification (conflict!)
        // Removed: launchRingingActivity() — direct start blocks/complicates FSI
        // The service's startForeground() with setFullScreenIntent() is THE only fullscreen trigger
        AlarmForegroundService.start(context, alarmId, alarm.timeLabel, alarm.label)

        // v2.6.10: NO separate ringing notification anymore — the service notification already has FSI
        // v2.6.10: NO direct activity launch anymore — let FSI do the work

        // NC-02 fix: Do NOT remove the fired alarm. Mark as disabled (ringing state).
        repo.store.save(alarm.copy(enabled = false))

        // v2.6.13 C1 + C1b + H7 fix: re-arm the next occurrence for repeat alarms.
        // - C1: the guard `skipCount == 0` has been REMOVED. A skip is consumed in
        //   PreAlarmReceiver.handleSkip (the record moves directly to the next
        //   occurrence with skipCount=0), so skipCount > 0 here would block the chain.
        //   After a skip the next occurrence MUST re-arm.
        // - C1b: originalTriggerMillis = nextTrigger (not the old T0), so a dismiss
        //   of a snooze does not cancel the whole repeat chain (see handleDismiss).
        // - H7: on a snooze (isSnooze=true) NO re-arm — the re-arm was already done at
        //   the original firing; an extra re-arm would duplicate the alarm.
        if (alarm.repeatDays.isNotEmpty() && !alarm.isSnooze) {
            val nextTrigger = repo.computeNextTriggerAfterToday(alarm.timeLabel, alarm.repeatDays)
            if (nextTrigger != null) {
                val zone = java.time.ZoneId.systemDefault()
                val nextDate = java.time.LocalDate.ofInstant(nextTrigger, zone).toString()
                val nextAlarm = alarm.copy(
                    id = repo.store.nextId(),
                    triggerAtMillis = nextTrigger.toEpochMilli(),
                    date = nextDate,
                    enabled = true,
                    isSnooze = false,
                    skipCount = 0,  // v2.6.13 C1: skip already consumed when shifting
                    originalTriggerMillis = nextTrigger.toEpochMilli()  // v2.6.13 C1b: new chain anchor
                )
                // v2.6.13 L3: automatic re-arm after firing is a system event → force=true (silent).
                // Without force, every morning would show an "Alarm changed" for an unchanged repeat alarm.
                val scheduled = repo.schedule(nextAlarm, force = true)
                if (scheduled != null) {
                    AppLogger.i(TAG, "v2.6.13 C1/C1b/H7 Repeat alarm: scheduled next occurrence id=${nextAlarm.id} for $nextDate at ${alarm.timeLabel} (orig=${nextAlarm.originalTriggerMillis})")
                } else {
                    AppLogger.e(TAG, "v2.6.13 Failed to schedule next occurrence for repeat alarm id=$alarmId")
                }
            } else {
                AppLogger.w(TAG, "v2.6.13 No next trigger found for repeat alarm id=$alarmId (${alarm.timeLabel})")
            }
        }

        AppLogger.i(TAG, "Alarm fired: id=$alarmId time=${alarm.timeLabel}")
    }

    /**
     * v2.6.7: Launch the ringing activity — EXACT copy of v2.5.13 working version.
     * Flags: 335544320 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP
     * NO extra flags — they broke AlarmClockInfo full-screen display.
     */
    private fun launchRingingActivity(
        context: Context,
        alarmId: Int,
        timeLabel: String,
        alarmLabel: String,
        retryCount: Int = 0
    ) {
        AppLogger.i(TAG, "v2.6.7: launchRingingActivity() — alarmId=$alarmId (attempt ${retryCount + 1})")

        val activityIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            // v2.6.7: EXACT v2.5.13 flags — nothing more, nothing less
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        try {
            context.startActivity(activityIntent)
            AppLogger.i(TAG, "v2.6.7: Ringing activity launched successfully (alarmId=$alarmId)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "v2.6.7: Failed to start ringing activity: ${e.javaClass.name}: ${e.message}")

            // Retry once after 500ms (Samsung sometimes blocks first attempt)
            if (retryCount < 1) {
                AppLogger.w(TAG, "Retrying activity launch in 500ms... (attempt ${retryCount + 1}/1)")
                android.os.Handler(context.mainLooper).postDelayed({
                    launchRingingActivity(context, alarmId, timeLabel, alarmLabel, retryCount + 1)
                }, 500)
            } else {
                AppLogger.e(TAG, "Failed to start ringing activity after retry — notification is fallback")
            }
        }
    }

    private fun handleSnooze(context: Context, alarmId: Int) {
        // v2.5.7 Story 3: Snooze is ALWAYS allowed — it's a response to an active alarm,
        // not a schedule change. Blocking snooze would trap the user with a ringing alarm.
        // The freeze prevents schedule changes (create/edit/delete), not alarm responses.
        val repo = AlarmRepository(context)
        // v2.5.7 Story 1: Stop the foreground service (stops sound, vibration, wake lock)
        AlarmForegroundService.stop(context)

        val alarm = repo.store.get(alarmId)

        if (alarm == null) {
            AppLogger.w(TAG, "Cannot snooze: alarm $alarmId not found")
            io.github.calendaralarm.service.AlarmNotificationHelper.cancelAlarmRingingNotification(context, alarmId)
            return
        }

        // F6: Read snooze duration from settings
        val snoozeMinutes = getSnoozeMinutes(context)
        val snoozeTrigger = System.currentTimeMillis() + snoozeMinutes * 60_000
        val snoozed = alarm.copy(
            id = repo.store.nextId(),
            triggerAtMillis = snoozeTrigger,
            enabled = true,
            isSnooze = true,
            originalTriggerMillis = if (alarm.isSnooze) alarm.originalTriggerMillis else alarm.triggerAtMillis
        )

        // Schedule snooze FIRST, then remove old entry (HN-02 pattern: don't destroy before replacement)
        // v2.5.7 Story 3: Use force=true — snooze must work even during freeze (safety)
        val scheduled = repo.schedule(snoozed, force = true)
        if (scheduled != null) {
            repo.store.remove(alarmId)
            AppLogger.i(TAG, "Snoozed: id=$alarmId → ${snoozed.id} (${snoozeMinutes} min)")
        } else {
            AppLogger.e(TAG, "Failed to schedule snooze — keeping original entry")
        }

        io.github.calendaralarm.service.AlarmNotificationHelper.cancelAlarmRingingNotification(context, alarmId)
    }

    private fun handleDismiss(context: Context, alarmId: Int) {
        // v2.5.7 Story 3: Dismiss is ALWAYS allowed — safety-critical operation.
        // Blocking dismiss during freeze would trap the user with a ringing alarm.
        // The freeze is meant to prevent accidental SCHEDULE changes, not to prevent
        // stopping an active alarm.
        val repo = AlarmRepository(context)
        // v2.5.7 Story 1: Stop the foreground service (stops sound, vibration, wake lock)
        AlarmForegroundService.stop(context)

        val alarm = repo.store.get(alarmId)

        // v2.5.7 Story 3: Use force=true — dismiss must work even during freeze (safety)
        if (alarm != null) {
            repo.cancel(alarmId, force = true)
            // v2.6.13 C1b FIX: the cancel cascade now filters on isSnooze == true, so ONLY
            // snooze records with the same originalTriggerMillis are cancelled — NOT the
            // already re-armed occurrences of next week. Before this fix every re-arm had
            // originalTriggerMillis = T0 (the old one), so dismiss of a snooze wiped the ENTIRE
            // repeat chain. Now originalTriggerMillis at the re-arm = nextTrigger
            // (see handleAlarmFired), but the extra filter is defense-in-depth.
            if (alarm.isSnooze) {
                repo.store.getAll()
                    .filter { it.isSnooze && it.originalTriggerMillis == alarm.originalTriggerMillis && it.id != alarmId }
                    .forEach { repo.cancel(it.id, force = true) }
                AppLogger.i(TAG, "v2.6.13 C1b: dismiss of snooze id=$alarmId cancels only snooze cascades (orig=${alarm.originalTriggerMillis})")
            }
        } else {
            repo.cancel(alarmId, force = true) // best effort even if not in store
        }

        io.github.calendaralarm.service.AlarmNotificationHelper.cancelAlarmRingingNotification(context, alarmId)
        AppLogger.i(TAG, "Alarm dismissed: id=$alarmId")
    }
}
