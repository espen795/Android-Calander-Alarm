package io.github.calendaralarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import io.github.calendaralarm.receiver.AlarmWidgetProvider
import io.github.calendaralarm.receiver.OwnedAlarmReceiver
import io.github.calendaralarm.receiver.PreAlarmReceiver
import io.github.calendaralarm.ui.AlarmRingingActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Repository for app-owned alarms scheduled via AlarmManager.setAlarmClock().
 *
 * Unlike the Samsung Clock intent approach, these alarms are fully owned by the app:
 * they fire unattended, can be listed/cancelled/replaced programmatically, and support
 * per-date replacement for the nightly smart-alarm flow (fixes C-06 and H-13).
 *
 * v2.1.0 additions:
 * - F1: toggle enabled/disabled via setEnabled()
 * - F4: Pre-alarm scheduling (60 min before alarm) via schedulePreAlarm()
 * - F5: Skip logic for repeat alarms (skipCount in store)
 */
class AlarmRepository(private val context: Context) {

    // v2.2.3: Use applicationContext for widget compatibility
    private val appContext: Context = context.applicationContext

    private val alarmManager: AlarmManager by lazy {
        val service = appContext.getSystemService(Context.ALARM_SERVICE)
        requireNotNull(service as? AlarmManager) {
            "AlarmManager service not available"
        }
    }
    val store = AlarmStore(appContext)

    /**
     * v2.3.0: Update the widget cache + trigger a widget refresh.
     * Call this after EVERY change to alarms (schedule, cancel, setEnabled, etc.)
     * WidgetCache is an object (singleton) — call it directly, no instance needed.
     */
    private fun updateWidget() {
        try {
            val nextAlarm = store.getNext()
            WidgetCache.updateNextAlarm(appContext, nextAlarm)
            AlarmWidgetProvider.updateAllWidgets(appContext)
        } catch (e: Exception) {
            AppLogger.e(TAG, "updateWidget failed: ${e.message}")
        }
    }

    // P1 review#1: state-prefs in device-protected storage (readable before unlock)
    private val statePrefs: SharedPreferences by lazy {
        DeviceProtectedStorage.prefs(appContext, PREFS_NAME)
    }

    companion object {
        private const val TAG = "AlarmRepository"
        const val EXTRA_ALARM_ID = "calendar_alarm_id"
        const val PRE_ALARM_OFFSET_MS = 60L * 60L * 1000L // F4: 60 minutes before alarm

        // v2.6.0: Keys for SharedPreferences (last state of alarms for change detection)
        private const val PREFS_NAME = "AlarmRepositoryState"
        private const val KEY_LAST_ALARM_STATE_PREFIX = "last_alarm_state_"
        /** N-c: trigger shorter than this in the past → fire now (not "missed"). */
        const val MISSED_GRACE_MS = 10 * 60 * 1000L
    }

    // ============== v2.5.8: Alarm Freeze (kept for future use) ==============
    // NOTE: checkFreeze() is no longer called from schedule/setEnabled/cancel.
    // Freeze is only enforced in AlarmSyncWorker (automated sync).
    // These methods are kept for potential future use.

    /**
     * v2.5.8: Check if Alarm Freeze is currently active.
     * Returns true if changes would be ALLOWED (not frozen).
     * NOTE: Not currently used for blocking — freeze only applies to automated sync.
     */
    fun checkFreeze(): Boolean {
        return try {
            val settings = io.github.calendaralarm.CalendarAlarmApp.settingsRepository
            if (settings.isWekkerFrozen()) {
                AppLogger.w(TAG, "Alarm change blocked: ${settings.getWekkerFreezeMessage()}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            // Fail-open: if we can't check settings, allow the change
            AppLogger.e(TAG, "Freeze check failed, allowing change: ${e.message}")
            true
        }
    }

    /**
     * v2.5.8: Get the freeze message for user display, or null if not frozen.
     * NOTE: Not currently used for blocking user actions.
     */
    fun getFreezeMessage(): String? {
        return try {
            val settings = io.github.calendaralarm.CalendarAlarmApp.settingsRepository
            if (settings.isWekkerFrozen()) settings.getWekkerFreezeMessage() else null
        } catch (e: Exception) {
            null
        }
    }

    // ============== Permission ==============

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun openExactAlarmSettings(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        }
    }

    // ============== Scheduling ==============

    /**
     * v2.6.12: State key for change dedup. Deliberately WITHOUT triggerAtMillis: when a
     * repeat alarm is rescheduled, triggerAtMillis shifts (next day), but
     * the alarm is substantively unchanged — that must NOT post a notification (boot reschedule stays silent).
     * Public/shared so unit tests can verify the key.
     */
    fun alarmStateKey(alarm: AlarmStore.OwnedAlarm): String =
        "${alarm.timeLabel}|${alarm.repeatDays.sorted()}|${alarm.label}|${alarm.enabled}"

    /**
     * v2.6.0/v2.6.12: Only show a notification if the alarm actually changed.
     * v2.6.12: goes through the central funnel (AlarmNotificationHelper.notifyAlarmEvent).
     * @param force true = system/boot reschedule → NEVER notify (silent).
     */
    private fun showNotificationIfChanged(alarm: AlarmStore.OwnedAlarm, force: Boolean = false) {
        if (force) {
            // v2.6.12: boot/system reschedule is silent — but DO save the state so that a
            // later real change relative to this state is detected.
            val prefs = statePrefs
            prefs.edit().putString("${KEY_LAST_ALARM_STATE_PREFIX}${alarm.id}", alarmStateKey(alarm)).apply()
            AppLogger.d(TAG, "force=true (boot/system) — notification suppressed (id=${alarm.id})")
            return
        }
        val prefs = statePrefs
        val lastStateKey = "${KEY_LAST_ALARM_STATE_PREFIX}${alarm.id}"
        val lastState = prefs.getString(lastStateKey, null)

        // v2.6.12: state key without triggerAtMillis (see alarmStateKey)
        val currentState = alarmStateKey(alarm)

        if (lastState != currentState) {
            // State changed → show notification
            io.github.calendaralarm.service.AlarmNotificationHelper.notifyAlarmEvent(
                appContext,
                io.github.calendaralarm.service.AlarmNotificationHelper.AlarmEvent.CHANGED,
                time = alarm.timeLabel,
                label = alarm.label,
                alarmId = alarm.id
            )

            // Save the new state
            prefs.edit().putString(lastStateKey, currentState).apply()
            AppLogger.d(TAG, "Alarm changed, notification shown (id=${alarm.id})")
        } else {
            AppLogger.d(TAG, "Alarm not changed, skipping notification (id=${alarm.id})")
        }
    }

    /**
     * Schedule (or replace) an app-owned alarm.
     * Returns the persisted [AlarmStore.OwnedAlarm], or null if exact-alarm permission is missing.
     *
     * F4: Also schedules a pre-alarm notification 60 minutes before the alarm fires.
     *
     * v2.5.8: Alarm Freeze NO LONGER blocks scheduling from this method.
     * Freeze only blocks AUTOMATED sync (AlarmSyncWorker checks freeze at start).
     * All user-initiated scheduling (UI) must work 24/7.
     * The [force] parameter is kept for future use but no longer triggers freeze checks.
     */
    fun schedule(alarm: AlarmStore.OwnedAlarm, force: Boolean = false): AlarmStore.OwnedAlarm? =
        AlarmTransactions.run { scheduleInternal(alarm, force) }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun scheduleInternal(alarm: AlarmStore.OwnedAlarm, force: Boolean = false): AlarmStore.OwnedAlarm? {
        AppLogger.d(TAG, "schedule() called: ${alarm.timeLabel} on ${alarm.date} (${alarm.label})")
        // v2.5.8: No freeze check here — user actions must always work
        // Automated sync is blocked at the AlarmSyncWorker level
        if (!canScheduleExactAlarms()) {
            AppLogger.e(TAG, "Cannot schedule: exact alarm permission NOT granted")
            return null
        }

        // v2.6.4: Show intent with Samsung-compatible flags for AlarmClockInfo
        // This is the PRIMARY way the system will show the alarm — Android handles
        // the full-screen display when the alarm fires via AlarmClockInfo.showIntent
        val showIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarm.id)
            // v2.6.4: Use singleInstance-compatible flags (matches manifest launchMode)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        val showPi = PendingIntent.getActivity(
            context, alarm.id, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val opIntent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val opPi = PendingIntent.getBroadcast(
            context, alarm.id, opIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(alarm.triggerAtMillis, showPi),
                opPi
            )
            store.save(alarm)
            AppLogger.i(TAG, "Alarm scheduled: id=${alarm.id} at=${alarm.timeLabel} (${alarm.label})")

            // F4: Schedule pre-alarm notification (60 min before)
            schedulePreAlarm(alarm)

            // v2.3.0: Widget cache update + refresh
            updateWidget()

            // v2.6.12: Only show a notification on change AND only if !force (boot/system silent)
            showNotificationIfChanged(alarm, force = force)

            return alarm
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "SecurityException scheduling alarm id=${alarm.id}")
            return null
        }
    }

    /**
     * F4: Schedule a pre-alarm notification 60 minutes before the alarm fires.
     * The pre-alarm uses a separate PendingIntent with a unique request code.
     */
    private fun schedulePreAlarm(alarm: AlarmStore.OwnedAlarm) {
        val preAlarmTime = alarm.triggerAtMillis - PRE_ALARM_OFFSET_MS
        if (preAlarmTime <= io.github.calendaralarm.data.AppClock.now()) {
            AppLogger.d(TAG, "Pre-alarm time already past for alarm id=${alarm.id}, skipping")
            return
        }

        // Use a unique request code for the pre-alarm PendingIntent
        // Offset by 500000 to avoid collision with regular alarm IDs
        val preAlarmRequestCode = alarm.id + 500000

        val preAlarmIntent = Intent(context, PreAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val preAlarmPi = PendingIntent.getBroadcast(
            context, preAlarmRequestCode, preAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                preAlarmTime,
                preAlarmPi
            )
            // Update store with the pre-alarm request code
            store.updatePreAlarmId(alarm.id, preAlarmRequestCode)
            AppLogger.d(TAG, "Pre-alarm scheduled for id=${alarm.id} at ${preAlarmTime} (requestCode=$preAlarmRequestCode)")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "SecurityException scheduling pre-alarm for id=${alarm.id}")
        }
    }

    /**
     * F4: Cancel the pre-alarm PendingIntent for an alarm.
     */
    private fun cancelPreAlarm(alarmId: Int, preAlarmRequestCode: Int) {
        if (preAlarmRequestCode < 0) return
        val preAlarmIntent = Intent(context, PreAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val preAlarmPi = PendingIntent.getBroadcast(
            context, preAlarmRequestCode, preAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.cancel(preAlarmPi)
            AppLogger.d(TAG, "Pre-alarm cancelled for alarm id=$alarmId (requestCode=$preAlarmRequestCode)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error cancelling pre-alarm for id=$alarmId")
        }
    }

    /**
     * Schedule a one-shot alarm for the next occurrence of [timeLabel] (optionally on [days]).
     *
     * F5: If [days] is not empty (repeat alarm), checks for an existing alarm with the same
     * timeLabel + repeatDays and skipCount > 0. If found, decrements skipCount and does NOT
     * schedule for today — instead finds the next matching day.
     */
    fun scheduleOneShot(
        timeLabel: String,
        label: String,
        days: List<Int> = emptyList(),
        type: String = "manual",
        targetDate: LocalDate? = null,
        snoozeMinutesOverride: Int? = null  // v2.5.0: per-alarm snooze override (null = use global)
    ): AlarmStore.OwnedAlarm? = AlarmTransactions.run {
        scheduleOneShotInternal(timeLabel, label, days, type, targetDate, snoozeMinutesOverride)
    }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun scheduleOneShotInternal(
        timeLabel: String,
        label: String,
        days: List<Int> = emptyList(),
        type: String = "manual",
        targetDate: LocalDate? = null,
        snoozeMinutesOverride: Int? = null
    ): AlarmStore.OwnedAlarm? {
        // F5 + FH-01 fix: Check for existing repeat alarm with skipCount > 0
        if (days.isNotEmpty()) {
            val existing = store.getAll().firstOrNull {
                it.timeLabel == timeLabel &&
                it.repeatDays.sorted() == days.sorted() &&
                it.skipCount > 0 &&
                it.enabled
            }
            if (existing != null) {
                // Consume one skip
                val decremented = store.decrementSkipCount(existing.id)
                val newSkipCount = decremented?.skipCount ?: (existing.skipCount - 1)
                AppLogger.i(TAG, "F5 Skip deze keer: consuming skip for alarm id=${existing.id} (${existing.timeLabel}), remaining=$newSkipCount")
                // Find next matching day AFTER today
                val trigger = computeNextTriggerAfterToday(timeLabel, days) ?: return null
                val zone = ZoneId.systemDefault()
                val dateStr = LocalDate.ofInstant(trigger, zone).toString()
                // FH-01 fix: Update the EXISTING alarm in-place (same ID) instead of
                // creating a new entry with store.nextId(). This prevents duplicate
                // alarm entries accumulating in the store.
                val updated = existing.copy(
                    triggerAtMillis = trigger.toEpochMilli(),
                    date = dateStr,
                    skipCount = newSkipCount,
                    enabled = true
                )
                return schedule(updated)
            }
        }

        val trigger = computeNextTrigger(timeLabel, days, targetDate) ?: return null
        val zone = ZoneId.systemDefault()
        val dateStr = LocalDate.ofInstant(trigger, zone).toString()
        val alarm = AlarmStore.OwnedAlarm(
            id = store.nextId(),
            triggerAtMillis = trigger.toEpochMilli(),
            timeLabel = timeLabel,
            label = label,
            date = dateStr,
            type = type,
            repeatDays = days,
            snoozeMinutesOverride = snoozeMinutesOverride  // v2.5.0
        )
        return schedule(alarm)
    }

    /**
     * Replace any existing smart-alarm for [date] with a new one-shot alarm.
     * Used by the nightly smart-alarm flow so only one alarm per day exists (fixes H-13).
     *
     * HN-02 fix: Schedule the NEW alarm FIRST, then cancel the old ones.
     * This way, if scheduling fails (permission/clock error), the old alarm survives.
     * Only smart-type alarms are replaced — manual alarms for the same date are kept.
     *
     * F5: Resets skipCount for the replaced alarm (server pushed a new alarm for this date).
     *
     * v2.6.13 (L1, recon RECON-notifications-2026-11-12): idempotent + silent.
     * - [silent] (default true): the sync path lets the repository notify NOTHING; the caller
     *   (AlarmSyncWorker) is the ONLY notifier and computes SET/CHANGED/null itself before apply.
     * - Identical plan → existing alarm re-armed with force=true (silent), rest force-cancelled,
     *   return same. No new ID, so the state-key dedup in showNotificationIfChanged
     *   works and "set to 10, still 10" produces 0 notifications.
     * - Changed plan → reuse existing ID (setAlarmClock with same requestCode +
     *   FLAG_UPDATE_CURRENT replaces atomically; store.save only after success → HN-02 still holds),
     *   schedule(newAlarm, force=silent), old cancels with force=true ("replaced" ≠ "removed").
     */
    fun replaceForDate(
        date: LocalDate,
        timeLabel: String,
        label: String,
        type: String,
        silent: Boolean = true
    ): AlarmStore.OwnedAlarm? = AlarmTransactions.run {
        replaceForDateInternal(date, timeLabel, label, type, silent)
    }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun replaceForDateInternal(
        date: LocalDate,
        timeLabel: String,
        label: String,
        type: String,
        silent: Boolean = true
    ): AlarmStore.OwnedAlarm? {
        val dateStr = date.toString()
        val zone = ZoneId.systemDefault()
        val localTime = LocalTime.parse(timeLabel)
        val trigger = ZonedDateTime.of(date, localTime, zone).toInstant()

        // HN-02: Reject past trigger times
        if (trigger.toEpochMilli() <= io.github.calendaralarm.data.AppClock.now()) {
            AppLogger.w(TAG, "Rejecting alarm with past trigger time: $timeLabel on $dateStr")
            return null
        }

        // v2.6.13 L1: existing smart alarms for this date (manual/test/snooze excluded)
        val existing = store.getAll().filter {
            it.date == dateStr && it.type != "manual" && it.type != "test" && !it.isSnooze
        }

        // v2.6.13 L1: idempotent — identical plan → notify nothing, only make sure
        // AlarmManager is armed (silently), and silently clean up any duplicates.
        // v2.6.13 H3: match regardless of `enabled` — a user-disabled alarm must also be
        // found, so that the sync does NOT re-enable it (see guard below).
        val same = existing.firstOrNull {
            it.timeLabel == timeLabel && it.label == label &&
                it.triggerAtMillis == trigger.toEpochMilli()
        }
        if (same != null) {
            if (!same.enabled && same.userDisabled) {
                // v2.6.13 H3: the USER manually disabled this smart alarm (UI toggle,
                // userDisabled=true). The sync must NOT override the user's choice:
                // the alarm stays off and no notification is posted. Do make sure the
                // AlarmManager entry is not (or no longer) armed.
                cancelAlarmManagerOnly(same.id, same.preAlarmId)
                existing.filter { it.id != same.id }.forEach { cancel(it.id, force = true) }
                AppLogger.i(TAG, "v2.6.13 H3: user-disabled alarm for $dateStr — sync does NOT re-enable (id=${same.id})")
                return same
            }
            if (!same.enabled) {
                // P2B review#7: auto-disabled (enabled=false, userDisabled=false — e.g. just
                // fired via handleAlarmFired, ringing state) with an IDENTICAL plan for a
                // FUTURE date. The sync re-arms it in AlarmManager — then the record
                // must also be saved with enabled=true, otherwise AlarmManager is armed but
                // the store says disabled (inconsistent; UI shows "off" while the alarm does fire).
                // For TODAY this path is never reached: AlarmSyncWorker B1-guard skips
                // today when there is no pending alarm (a fired record is not pending).
                val reEnabled = same.copy(enabled = true)
                schedule(reEnabled, force = true)
                existing.filter { it.id != same.id }.forEach { cancel(it.id, force = true) }
                AppLogger.i(TAG, "P2B review#7: auto-disabled alarm for $dateStr — sync re-enables consistently (AlarmManager + store, id=${same.id})")
                return reEnabled
            }
            schedule(same, force = true)
            existing.filter { it.id != same.id }.forEach { cancel(it.id, force = true) }
            AppLogger.i(TAG, "v2.6.13 L1: identical plan for $dateStr — re-armed id=${same.id} silently")
            return same
        }

        // v2.6.13 H3: if the USER disabled an existing smart alarm for this date
        // (userDisabled=true, e.g. "no alarm tomorrow"), then the sync must NOT
        // override that choice with a new enabled alarm. We return the existing (disabled)
        // alarm — the worker then sees enabled=false and may notify CHANGED,
        // but the alarm itself stays OFF as the user chose.
        val userDisabledAlarm = existing.firstOrNull { it.userDisabled }
        if (userDisabledAlarm != null) {
            AppLogger.i(TAG, "v2.6.13 H3: user-disabled alarm for $dateStr (id=${userDisabledAlarm.id}) — sync does NOT override the user's choice")
            cancelAlarmManagerOnly(userDisabledAlarm.id, userDisabledAlarm.preAlarmId)
            existing.filter { it.id != userDisabledAlarm.id }.forEach { cancel(it.id, force = true) }
            return userDisabledAlarm
        }

        // v2.6.13 L1: reuse ID → state-key dedup works, no ID growth per sync
        val newAlarm = AlarmStore.OwnedAlarm(
            id = existing.firstOrNull()?.id ?: store.nextId(),
            triggerAtMillis = trigger.toEpochMilli(),
            timeLabel = timeLabel,
            label = label,
            date = dateStr,
            type = type
        )

        // Step 1: Schedule the NEW alarm first (silent when silent — the worker notifies)
        val scheduled = schedule(newAlarm, force = silent)
        if (scheduled == null) {
            AppLogger.e(TAG, "Failed to schedule replacement alarm — keeping existing alarms")
            return null
        }

        // F5: Reset skipCount for any existing alarm with same timeLabel + date
        store.getAll()
            .filter { it.date == dateStr && it.timeLabel == timeLabel && it.skipCount > 0 }
            .forEach { store.resetSkipCount(it.id) }

        // Step 2: Only after success, cancel old smart alarms for this date
        // (but NOT manual alarms, and NOT snoozes of the new alarm).
        // v2.6.13 L1: always force=true — "replaced" is not "removed", so silent.
        store.getAll()
            .filter { it.date == dateStr && it.id != newAlarm.id && it.type != "manual" && it.type != "test" }
            .forEach { cancel(it.id, force = true) }

        AppLogger.i(TAG, "Replaced alarm for $dateStr: new id=${newAlarm.id} at $timeLabel (silent=$silent)")
        return scheduled
    }

    /**
     * F1: Toggle an alarm's enabled state.
     * When disabling: cancels the AlarmManager entry but keeps it in the store.
     * When enabling: re-schedules via [schedule].
     *
     * v2.5.8: Alarm Freeze NO LONGER blocks this — user toggle must work 24/7.
     *
     * v2.6.13 H3: [userAction] (default true — the only caller is the UI toggle in
     * MainScreen) marks the alarm as user-disabled when turning it off, so that the
     * sync (replaceForDate) does not override the user's choice. When turning it on,
     * the flag is cleared.
     */
    fun setEnabled(id: Int, enabled: Boolean, force: Boolean = false, userAction: Boolean = true): Boolean =
        AlarmTransactions.run { setEnabledInternal(id, enabled, force, userAction) }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun setEnabledInternal(id: Int, enabled: Boolean, force: Boolean = false, userAction: Boolean = true): Boolean {
        // v2.5.8: No freeze check — user actions must always work
        val alarm = store.get(id) ?: return false
        if (enabled) {
            // Re-schedule — clear userDisabled (user turns the alarm back on themselves)
            val updated = alarm.copy(enabled = true, userDisabled = false)
            // v2.6.12: pass force through so boot reschedule via setEnabled is also silent
            val result = schedule(updated, force = force)
            return result != null
        } else {
            // Cancel AlarmManager entry but keep in store
            cancelAlarmManagerOnly(id, alarm.preAlarmId)
            if (userAction) {
                // v2.6.13 H3: mark as user-disabled → sync must NOT re-enable this
                store.setUserDisabled(id, true)
            } else {
                store.setEnabled(id, false)
            }
            AppLogger.i(TAG, "Alarm disabled: id=$id (${alarm.timeLabel}), userAction=$userAction")
            // v2.3.0: Widget cache update
            updateWidget()

            // v2.6.12: Only show a notification on change (enabled is part of the state key)
            showNotificationIfChanged(alarm.copy(enabled = false), force = force)

            return true
        }
    }

    /**
     * FC-02 fix: Cancel only the AlarmManager entries (main + pre-alarm) without removing from store.
     * Made public so PreAlarmReceiver.handleSkip() can cancel the alarm trigger
     * while keeping the alarm enabled in the store (so skipCount can be consumed later).
     */
    fun cancelAlarmManagerOnly(id: Int, preAlarmRequestCode: Int = -1) {
        val opIntent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, id)
        }
        val opPi = PendingIntent.getBroadcast(
            context, id, opIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.cancel(opPi)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error cancelling AlarmManager entry for id=$id")
        }

        // F4: Also cancel pre-alarm
        cancelPreAlarm(id, preAlarmRequestCode)
    }

    /**
     * Cancel an alarm.
     *
     * v2.5.8: Alarm Freeze NO LONGER blocks this — user delete must work 24/7.
     */
    fun cancel(id: Int, force: Boolean = false): Boolean =
        AlarmTransactions.run { cancelInternal(id, force) }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun cancelInternal(id: Int, force: Boolean = false): Boolean {
        // v2.5.8: No freeze check — user actions must always work
        val alarm = store.get(id) ?: return false
        val opIntent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, id)
        }
        val opPi = PendingIntent.getBroadcast(
            context, id, opIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            alarmManager.cancel(opPi)
            // F4: Also cancel pre-alarm
            cancelPreAlarm(id, alarm.preAlarmId)
            store.remove(id)
            AppLogger.i(TAG, "Alarm cancelled: id=$id")
            // v2.3.0: Widget cache update + refresh
            updateWidget()

            // v2.6.12: "Alarm removed" (E3) — ONLY if the alarm really existed (here: yes,
            // because store.get(id) != null) and ONLY if !force (boot/system cancel = silent).
            // Clear the saved state so that a new alarm with the same ID does get a notification.
            val prefs = statePrefs
            prefs.edit().remove("${KEY_LAST_ALARM_STATE_PREFIX}$id").apply()
            if (!force) {
                io.github.calendaralarm.service.AlarmNotificationHelper.notifyAlarmEvent(
                    appContext,
                    io.github.calendaralarm.service.AlarmNotificationHelper.AlarmEvent.REMOVED,
                    time = alarm.timeLabel,
                    label = alarm.label,
                    alarmId = id
                )
            } else {
                AppLogger.d(TAG, "force=true — removal notification suppressed (id=$id)")
            }

            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error cancelling alarm id=$id")
            false
        }
    }

    /**
     * F6: Cancel all smart alarms for a specific date (not manual/test alarms).
     * Used when server says "no alarm" (e.g. weekend without weekend rule).
     * Returns the number of cancelled alarms.
     *
     * v2.6.13 (L4): [force] is passed through to cancel() — the sync path (worker) uses
     * force=true and itself posts 1× aggregate REMOVED (only if something was really removed),
     * so that no duplicate per-alarm REMOVED notifications occur.
     */
    fun cancelForDate(date: LocalDate, force: Boolean = false): Int =
        AlarmTransactions.run { cancelForDateInternal(date, force) }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun cancelForDateInternal(date: LocalDate, force: Boolean = false): Int {
        val dateStr = date.toString()
        val toCancel = store.getAll()
            .filter { it.date == dateStr && it.type != "manual" && it.type != "test" }
        var count = 0
        toCancel.forEach { alarm ->
            if (cancel(alarm.id, force = force)) count++
        }
        if (count > 0) {
            AppLogger.i(TAG, "Cancelled $count alarm(s) for $dateStr (no-alarm day, force=$force)")
        }
        return count
    }

    fun cancelAll(): Int = AlarmTransactions.run { cancelAllInternal() }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun cancelAllInternal(): Int {
        val all = store.removeAll()
        all.forEach { alarm ->
            val opIntent = Intent(context, OwnedAlarmReceiver::class.java).apply {
                putExtra(EXTRA_ALARM_ID, alarm.id)
            }
            val opPi = PendingIntent.getBroadcast(
                context, alarm.id, opIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try { alarmManager.cancel(opPi) } catch (_: Exception) {}
            // F4: Also cancel pre-alarm
            cancelPreAlarm(alarm.id, alarm.preAlarmId)
        }
        // v2.3.0: Widget cache update + refresh
        updateWidget()
        return all.size
    }

    /**
     * FC-03 fix: Re-schedule all enabled alarms (e.g. after reboot or app update).
     * - Repeat alarms (repeatDays not empty) in the past: compute next trigger and reschedule
     * - One-shot alarms in the past: remove from store
     *
     * v2.5.7 Story 3: System operation — bypasses Alarm Freeze (force=true).
     * This runs on boot and must restore alarms even during freeze hours.
     */
    fun rescheduleAll() = AlarmTransactions.run { rescheduleAllInternal() }

    /** P2B review#9: echte body — draait binnen [AlarmTransactions] (reentrant). */
    private fun rescheduleAllInternal() {
        val now = io.github.calendaralarm.data.AppClock.now()
        store.getAll().forEach { alarm ->
            if (!alarm.enabled) return@forEach
            // N-c (review): a trigger that was < 10 min in the past (phone briefly off /
            // TIME_SET / boot just missed) → fire NOW (now+5s) instead of moving to
            // next week or reporting "missed". When in doubt: RING.
            if (alarm.triggerAtMillis <= now && now - alarm.triggerAtMillis < MISSED_GRACE_MS) {
                val fireAt = now + 5_000
                AppLogger.i(TAG, "N-c: alarm id=${alarm.id} (${alarm.timeLabel}) was ${now - alarm.triggerAtMillis}ms in the past (<10min) — firing NOW (now+5s)")
                val fireNow = alarm.copy(triggerAtMillis = fireAt, enabled = true)
                val scheduled = schedule(fireNow, force = true)
                if (scheduled == null) {
                    AppLogger.e(TAG, "N-c: could not schedule alarm id=${alarm.id} for now+5s — keeping record")
                    notifyRescheduleFailed(alarm)
                }
                return@forEach
            }
            if (alarm.triggerAtMillis <= now) {
                // FC-03: For repeat alarms, compute the next trigger instead of removing.
                // v2.6.13 C1 FIX: the guard `skipCount == 0` has been REMOVED. A skip is
                // consumed in PreAlarmReceiver.handleSkip itself (the record is immediately
                // moved to the next occurrence with skipCount reset), so a
                // repeat alarm with skipCount > 0 in the store is abnormal — at boot
                // we must STILL re-arm instead of deleting the record, otherwise
                // the repeat chain breaks silently after a skip + reboot.
                // P1 review#4/#5: use computeNextTrigger (FROM TODAY, so today
                // if the time has not passed yet) instead of computeNextTriggerAfterToday —
                // otherwise a repeat alarm fires a day late after a boot.
                if (alarm.repeatDays.isNotEmpty()) {
                    val nextTrigger = computeNextTrigger(alarm.timeLabel, alarm.repeatDays, null)
                    if (nextTrigger != null) {
                        val zone = ZoneId.systemDefault()
                        val nextDate = LocalDate.ofInstant(nextTrigger, zone).toString()
                        val updated = alarm.copy(
                            triggerAtMillis = nextTrigger.toEpochMilli(),
                            date = nextDate,
                            enabled = true,
                            skipCount = 0  // v2.6.13 C1: skip was already consumed when shifting
                        )
                        // P1 review#4/#5: FIRST schedule (same ID), only on success replace the
                        // record — NEVER remove before rescheduling (otherwise
                        // the alarm disappears on a schedule error).
                        val scheduled = schedule(updated, force = true)  // v2.5.7: system operation
                        if (scheduled != null) {
                            // schedule() already did store.save(updated) (same id) —
                            // record atomically replaced, no remove needed.
                            AppLogger.i(TAG, "P1 #4 rescheduleAll: repeat alarm id=${alarm.id} rescheduled to $nextDate at ${alarm.timeLabel} (was ${alarm.date}, skipCount was ${alarm.skipCount})")
                        } else {
                            // On failure: KEEP the record (do not delete!) + log + notification
                            AppLogger.e(TAG, "P1 #4 rescheduleAll: could NOT reschedule repeat alarm id=${alarm.id} — keeping record")
                            notifyRescheduleFailed(alarm)
                        }
                        return@forEach
                    }
                }
                // P1 review#5: missed one-shot alarm (past, still enabled) → do NOT silently
                // remove, but post "Alarm at HH:mm missed" and disable the record.
                // Fail-safe: the record stays (enabled=false) so it is visible
                // in the UI and the user can reactivate it manually.
                AppLogger.w(TAG, "P1 #5 rescheduleAll: one-shot alarm id=${alarm.id} missed (${alarm.timeLabel} ${alarm.date}) — notification + disable")
                store.setEnabled(alarm.id, false)
                notifyMissedAlarm(alarm)
                return@forEach
            }
            schedule(alarm, force = true)  // v2.5.7: system operation
        }
        // v2.3.0: Update widget after rescheduling all
        updateWidget()
    }

    /**
     * P1 review#5: notification that a one-shot alarm was missed (e.g. phone off
     * while it was firing). Notification via the central registry (MISSED_ALARM).
     */
    private fun notifyMissedAlarm(alarm: AlarmStore.OwnedAlarm) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    appContext, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val pi = android.app.PendingIntent.getActivity(
                appContext, 0,
                Intent(appContext, io.github.calendaralarm.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = androidx.core.app.NotificationCompat.Builder(appContext, io.github.calendaralarm.CalendarAlarmApp.CHANNEL_ALARMS)
                .setContentTitle("⏰ Alarm missed: ${alarm.timeLabel}")
                .setContentText("${alarm.label} (${alarm.date}) fired while the phone was off — it has been turned off")
                .setSmallIcon(io.github.calendaralarm.R.drawable.ic_notification)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            nm?.notify(io.github.calendaralarm.service.NotificationIds.MISSED_ALARM, n)
        } catch (e: Exception) {
            AppLogger.e(TAG, "P1: posting missed-alarm notification failed: ${e.message}")
        }
    }

    /**
     * P1 review#4/#5: notification + log when a repeat alarm could not be rescheduled.
     */
    private fun notifyRescheduleFailed(alarm: AlarmStore.OwnedAlarm) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    appContext, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val pi = android.app.PendingIntent.getActivity(
                appContext, 0,
                Intent(appContext, io.github.calendaralarm.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = androidx.core.app.NotificationCompat.Builder(appContext, io.github.calendaralarm.CalendarAlarmApp.CHANNEL_ALARMS)
                .setContentTitle("⚠️ Alarm reschedule failed: ${alarm.timeLabel}")
                .setContentText("${alarm.label} — open the app to schedule it again")
                .setSmallIcon(io.github.calendaralarm.R.drawable.ic_notification)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            nm?.notify(io.github.calendaralarm.service.NotificationIds.RESCHEDULE_FAILED, n)
        } catch (e: Exception) {
            AppLogger.e(TAG, "P1: posting reschedule-failed notification failed: ${e.message}")
        }
    }

    fun getNextAlarm(): AlarmStore.OwnedAlarm? = store.getNext()

    /**
     * v2.3.0: Public method to refresh the widget cache.
     * Use from MainActivity.onResume() and BootReceiver.
     */
    fun refreshWidget() {
        updateWidget()
    }

    // ============== Helpers (public for testing) ==============

    /**
     * Compute the next trigger time for a given time label and optional repeat days.
     * Returns null if the time format is invalid.
     *
     * Made public for unit testing (F7).
     */
    fun computeNextTrigger(
        timeLabel: String,
        days: List<Int>,
        targetDate: LocalDate?,
        nowMillis: Long = AppClock.now()
    ): java.time.Instant? {
        val zone = ZoneId.systemDefault()
        val localTime = try { LocalTime.parse(timeLabel) } catch (e: Exception) { return null }

        if (targetDate != null) {
            return ZonedDateTime.of(targetDate, localTime, zone).toInstant()
        }

        // One clock source (System.currentTimeMillis) for all time decisions: previously
        // LocalDateTime.now() here and currentTimeMillis in rescheduleAll — two clocks. Now AppClock.
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        if (days.isEmpty()) {
            // One-shot: today if in future, else tomorrow
            var candidate = ZonedDateTime.of(now.toLocalDate(), localTime, zone)
            if (candidate.toInstant().toEpochMilli() <= now.atZone(zone).toInstant().toEpochMilli()) {
                candidate = candidate.plusDays(1)
            }
            return candidate.toInstant()
        }

        // Find next matching day-of-week (1=Mon..7=Sun → java.time 1=Mon..7=Sun)
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val dow = date.dayOfWeek.value // 1=Mon..7=Sun
            if (dow in days) {
                val candidate = ZonedDateTime.of(date, localTime, zone)
                if (candidate.toInstant().toEpochMilli() > now.atZone(zone).toInstant().toEpochMilli()) {
                    return candidate.toInstant()
                }
            }
        }
        return null
    }

    /**
     * F5: Compute the next trigger time for a repeat alarm, starting from TOMORROW.
     * Used when a skip is consumed — the alarm should fire on the next matching day,
     * not today (since the user just skipped today's occurrence).
     *
     * Made public for unit testing (F7).
     */
    fun computeNextTriggerAfterToday(
        timeLabel: String,
        days: List<Int>
    ): java.time.Instant? {
        val zone = ZoneId.systemDefault()
        val localTime = try { LocalTime.parse(timeLabel) } catch (e: Exception) { return null }

        if (days.isEmpty()) return null

        val tomorrow = java.time.Instant.ofEpochMilli(AppClock.now()).atZone(zone).toLocalDate().plusDays(1)

        // Search up to 8 days ahead starting from tomorrow
        for (offset in 0..7) {
            val date = tomorrow.plusDays(offset.toLong())
            val dow = date.dayOfWeek.value
            if (dow in days) {
                return ZonedDateTime.of(date, localTime, zone).toInstant()
            }
        }
        return null
    }

    /**
     * F4: Compute the pre-alarm trigger time (alarm time - 60 minutes).
     * Returns null if the result would be in the past.
     *
     * Made public for unit testing (F7).
     */
    fun computePreAlarmTime(triggerAtMillis: Long): Long? {
        val preAlarmTime = triggerAtMillis - PRE_ALARM_OFFSET_MS
        return if (preAlarmTime > io.github.calendaralarm.data.AppClock.now()) preAlarmTime else null
    }
}
