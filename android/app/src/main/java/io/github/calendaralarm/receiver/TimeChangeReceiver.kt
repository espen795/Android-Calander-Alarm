package io.github.calendaralarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmTransactions
import io.github.calendaralarm.data.AppLogger
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private const val TAG = "TimeChangeReceiver"

/**
 * P1 fix review#11: TIMEZONE_CHANGED + TIME_SET.
 *
 * On a timezone change (or manual time change), all alarms must be
 * recomputed FROM timeLabel + date (wall clock), NOT from the stored
 * epoch (which would then be wrong). rescheduleAll() does exactly that for
 * repeat alarms (computeNextTrigger uses timeLabel + systemDefault zone);
 * for future one-shots we recompute the trigger explicitly from date+timeLabel in the NEW zone.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, "android.intent.action.TIME_SET" -> {
                AppLogger.i(TAG, "P1 #11: ${intent.action} — recompute alarms from timeLabel+date (wall clock)")
                try {
                    val repo = AlarmRepository(context)
                    // P2B review#9: recompute + rescheduleAll atomically within the
                    // global alarm mutex (one transactional boundary, reentrant).
                    AlarmTransactions.run {
                        recomputeWallClockTriggers(repo)
                        repo.rescheduleAll()
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "P1 #11: recompute after ${intent.action} failed: ${e.message}")
                }
            }
        }
    }

    /**
     * N-d (review): recompute triggerAtMillis of every future alarm from
     * date+timeLabel (one-shots AND repeats; repeats only fall back to computeNextTrigger when date is invalid/in the past)
     * in the CURRENT zone — wall clock, not the old epoch. Snoozes are relative (now+duration)
     * and are left untouched. Past triggers are handled by rescheduleAll.
     */
    private fun recomputeWallClockTriggers(repo: AlarmRepository) {
        val zone = ZoneId.systemDefault()
        val now = io.github.calendaralarm.data.AppClock.now()
        repo.store.getAll().forEach { alarm ->
            if (!alarm.enabled) return@forEach
            if (alarm.isSnooze) return@forEach               // snooze is relative (now+duration), not wall clock
            if (alarm.triggerAtMillis <= now) return@forEach // past → rescheduleAll handles it
            try {
                // Re-review fix: also recompute repeat alarms from their own date+timeLabel (wall clock).
                // computeNextTrigger-from-now would undo a "Skip this time" (record deliberately on a
                // later date) on every TIME_SET/NTP correction.
                val wallClock = try {
                    ZonedDateTime.of(LocalDate.parse(alarm.date), LocalTime.parse(alarm.timeLabel), zone)
                        .toInstant().toEpochMilli()
                } catch (e: Exception) { null }
                val newTrigger = when {
                    wallClock != null && wallClock > now -> wallClock
                    alarm.repeatDays.isNotEmpty() ->
                        repo.computeNextTrigger(alarm.timeLabel, alarm.repeatDays, null)?.toEpochMilli()
                    else -> wallClock
                }
                if (newTrigger == null || newTrigger == alarm.triggerAtMillis) return@forEach
                run {
                    val updated = alarm.copy(triggerAtMillis = newTrigger)
                    // Schedule first (same id), then save — HN-02 pattern
                    val scheduled = repo.schedule(updated, force = true)
                    if (scheduled == null) {
                        AppLogger.e(TAG, "N-d/#11: could not reschedule alarm id=${alarm.id} after TZ change — keeping old record")
                    } else {
                        val newDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(newTrigger), zone).toString()
                        val updatedWithDate = updated.copy(date = newDate)
                        repo.store.save(updatedWithDate)
                        AppLogger.i(TAG, "N-d/#11: alarm id=${alarm.id} recomputed: ${alarm.timeLabel} → $newTrigger (was ${alarm.triggerAtMillis}, date=$newDate)")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "P1 #11: parse error while recomputing alarm id=${alarm.id}: ${e.message} — left untouched")
            }
        }
    }
}
