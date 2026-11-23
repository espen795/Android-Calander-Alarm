package io.github.calendaralarm.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.AlarmTransactions
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
            // B2 (review): read via settingsRepository — snooze_minutes is written
            // there (calendar_alarm_settings, DP since B1 → readable before unlock).
            // The earlier direct read from "alarm_settings" read a different file → always 5.
            return try {
                io.github.calendaralarm.CalendarAlarmApp.settingsRepository.getSnoozeMinutes().toLong()
            } catch (e: Exception) {
                // Fallback: direct from DP regular prefs (if settingsRepository is not
                // initialized, e.g. in a receiver before Application.onCreate)
                try {
                    io.github.calendaralarm.data.DeviceProtectedStorage.prefs(
                        context, "calendar_alarm_settings"
                    ).getInt("snooze_minutes", DEFAULT_SNOOZE_MINUTES.toInt()).toLong()
                } catch (e2: Exception) {
                    AppLogger.w(TAG, "Failed to read snooze minutes, using default")
                    DEFAULT_SNOOZE_MINUTES
                }
            }
        }

        /**
         * v2.7.0: Effective snooze duration for an alarm — the per-alarm override takes precedence over the
         * global setting (same rule as AlarmRingingActivity shows). 0 = snooze off.
         */
        fun effectiveSnoozeMinutes(context: Context, alarm: io.github.calendaralarm.data.AlarmStore.OwnedAlarm?): Long {
            return alarm?.snoozeMinutesOverride?.toLong() ?: getSnoozeMinutes(context)
        }

        /**
         * v2.7.0: Which action a physical button (volume / power) performs while ringing.
         * Setting "dismiss" (default) or "snooze"; snooze falls back to dismiss if snooze
         * is off for this alarm.
         */
        fun hardwareButtonAction(context: Context, alarmId: Int): String {
            val pref = io.github.calendaralarm.data.DeviceProtectedStorage.prefs(
                context, io.github.calendaralarm.CalendarAlarmApp.PREFS_ALARM_SETTINGS  // P1 #1: DP
            ).getString(
                io.github.calendaralarm.CalendarAlarmApp.PREF_HARDWARE_BUTTON_ACTION,
                io.github.calendaralarm.CalendarAlarmApp.HARDWARE_ACTION_DISMISS
            )
            if (pref != io.github.calendaralarm.CalendarAlarmApp.HARDWARE_ACTION_SNOOZE) return ACTION_DISMISS
            val alarm = try { AlarmRepository(context).store.get(alarmId) } catch (_: Exception) { null }
            return if (effectiveSnoozeMinutes(context, alarm) > 0) ACTION_SNOOZE else ACTION_DISMISS
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
        // P2B re-review B3: the service start is requested before the store mutations and
        // does not depend on store code that can fail; the mutations are in try/catch so that
        // an error there does not crash the receiver (and thus the service).
        // NB: onStartCommand (sound) only runs after onReceive is done (both main
        // thread) — the sound therefore waits briefly (ms, no network inside the lock) for the
        // mutations. Do NOT move to a background thread (goAsync): then a
        // quick dismiss could arrive before the re-arm and break the repeat chain.
        var label = "Alarm"
        var timeLabel = nowTimeLabel()
        try {
            val stored = AlarmRepository(context).store.get(alarmId)
            if (stored != null) {
                label = stored.label
                timeLabel = stored.timeLabel
            } else {
                AppLogger.e(TAG, "P1 review#3: Alarm $alarmId not found in store — RING ANYWAY (fail-safe) with default label")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "B3: reading the store failed before ringing (${e.message}) — RING ANYWAY with default label")
        }
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalendarAlarm:AlarmWakeLock")
                .acquire(30_000)
        } catch (e: Exception) {
            AppLogger.e(TAG, "B3: receiver wakelock failed (${e.message}) — service takes its own wakelock")
        }
        // The service's startForeground() with setFullScreenIntent() is THE only fullscreen trigger
        AlarmForegroundService.start(context, alarmId, timeLabel, label)

        try {
            AlarmTransactions.run { handleAlarmFiredLocked(context, alarmId) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "B3: store mutations after firing failed (${e.message}) — alarm still rings")
        }
    }

    private fun nowTimeLabel(): String = try {
        java.time.Instant.ofEpochMilli(io.github.calendaralarm.data.AppClock.now())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    } catch (e: Exception) { "nu" }

    /** Store mutations after firing (under AlarmTransactions). Does NOT start a service — that already happened. */
    private fun handleAlarmFiredLocked(context: Context, alarmId: Int) {
        AppLogger.i(TAG, "v2.6.10: handleAlarmFired() called — alarmId=$alarmId")

        val repo = AlarmRepository(context)
        val stored = repo.store.get(alarmId) ?: run {
            // Fallback alarm (no record): already ringing; nothing to save or re-arm.
            AppLogger.i(TAG, "P1 review#3: fallback alarm $alarmId — no store mutations")
            return
        }
        val alarm: AlarmStore.OwnedAlarm = stored
        AppLogger.i(TAG, "v2.6.10: Alarm found: ${alarm.timeLabel} - ${alarm.label} (store)")

        // NC-02 fix: Do NOT remove the fired alarm. Mark as disabled (ringing state).
        // P1 review#3: if the record was missing (fallback alarm), we do NOT save it —
        // we don't want to create a ghost record in the store that could later be
        // snoozed/dismissed as a "real" alarm. We only mark existing records.
        if (repo.store.get(alarmId) != null) {
            repo.store.save(alarm.copy(enabled = false))
        }

        // v2.6.13 C1 + C1b + H7 fix: re-arm the next occurrence for repeat alarms.
        // - C1: the guard `skipCount == 0` has been REMOVED. A skip is consumed in
        //   PreAlarmReceiver.handleSkip (the record immediately shifts to the next
        //   occurrence with skipCount=0), so skipCount > 0 here would block the chain.
        //   After a skip, the next occurrence MUST re-arm.
        // - C1b: originalTriggerMillis = nextTrigger (not the old T0), so that a dismiss
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
                    skipCount = 0,  // v2.6.13 C1: skip was already consumed when shifting
                    originalTriggerMillis = nextTrigger.toEpochMilli()  // v2.6.13 C1b: new chain anchor
                )
                // v2.6.13 L3: automatic re-arm after firing is a system event → force=true (silent).
                // Without force, every morning produced an "Alarm changed" for an unchanged repeat alarm.
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
        // P2B review#9: snooze chain (get → schedule snooze → remove old) atomic.
        AlarmTransactions.run { handleSnoozeLocked(context, alarmId) }
    }

    private fun handleSnoozeLocked(context: Context, alarmId: Int) {
        // v2.5.7 Story 3: Snooze is ALWAYS allowed — it's a response to an active alarm,
        // not a schedule change. Blocking snooze would trap the user with a ringing alarm.
        // The freeze prevents schedule changes (create/edit/delete), not alarm responses.
        val repo = AlarmRepository(context)
        // v2.5.7 Story 1: Stop the foreground service (stops sound, vibration, wake lock)
        AlarmForegroundService.stop(context)

        var alarm = repo.store.get(alarmId)

        if (alarm == null) {
            // B4 (review): snooze on a fallback alarm (fire without record, see #3)
            // must NOT permanently disable the alarm — still schedule a snooze with a
            // new id, same fail-safe fallback object as handleAlarmFired.
            AppLogger.w(TAG, "B4: snooze without store record for id=$alarmId — still scheduling snooze (fail-safe)")
            val now = io.github.calendaralarm.data.AppClock.now()
            val nowLabel = try {
                java.time.LocalTime.now(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e: Exception) { "nu" }
            alarm = AlarmStore.OwnedAlarm(
                id = alarmId,
                triggerAtMillis = now,
                timeLabel = nowLabel,
                label = "Alarm",
                date = java.time.Instant.ofEpochMilli(io.github.calendaralarm.data.AppClock.now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                type = "manual",
                enabled = true,
                originalTriggerMillis = now
            )
        }

        // v2.7.0: respect the per-alarm override (previously always the global value, while
        // the ringing screen showed the override). Snooze off (0) → treat as dismiss.
        val snoozeMinutes = effectiveSnoozeMinutes(context, alarm)
        if (snoozeMinutes <= 0) {
            AppLogger.i(TAG, "Snooze off for alarm $alarmId — dismiss instead of snooze")
            handleDismiss(context, alarmId)  // reentrant: already inside the lock
            return
        }
        val snoozeTrigger = io.github.calendaralarm.data.AppClock.now() + snoozeMinutes * 60_000
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
            // B4: only remove the old record if it DID exist (with a fallback
            // there was no record — removing a non-existent one is a no-op, but log it)
            if (repo.store.get(alarmId) != null) repo.store.remove(alarmId)
            AppLogger.i(TAG, "Snoozed: id=$alarmId → ${snoozed.id} (${snoozeMinutes} min)")
        } else {
            AppLogger.e(TAG, "Failed to schedule snooze — keeping original entry")
        }

        io.github.calendaralarm.service.AlarmNotificationHelper.cancelAlarmRingingNotification(context, alarmId)
    }

    private fun handleDismiss(context: Context, alarmId: Int) {
        // P2B review#9: dismiss chain (get → cancel → snooze cascade) atomic.
        AlarmTransactions.run { handleDismissLocked(context, alarmId) }
    }

    private fun handleDismissLocked(context: Context, alarmId: Int) {
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
            // already re-armed occurrences of next week. Before this fix every re-arm
            // had originalTriggerMillis = T0 (the old one), so dismissing a snooze wiped the WHOLE
            // repeat chain. Now originalTriggerMillis at the re-arm = nextTrigger
            // (see handleAlarmFired), but the extra filter is defense-in-depth.
            if (alarm.isSnooze) {
                repo.store.getAll()
                    .filter { it.isSnooze && it.originalTriggerMillis == alarm.originalTriggerMillis && it.id != alarmId }
                    .forEach { repo.cancel(it.id, force = true) }
                AppLogger.i(TAG, "v2.6.13 C1b: dismiss of snooze id=$alarmId only cancels snooze cascades (orig=${alarm.originalTriggerMillis})")
            }
        } else {
            repo.cancel(alarmId, force = true) // best effort even if not in store
        }

        io.github.calendaralarm.service.AlarmNotificationHelper.cancelAlarmRingingNotification(context, alarmId)
        AppLogger.i(TAG, "Alarm dismissed: id=$alarmId")
    }
}
