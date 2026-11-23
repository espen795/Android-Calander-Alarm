package io.github.calendaralarm.data

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.receiver.ExactAlarmPermissionReceiver
import io.github.calendaralarm.receiver.TimeChangeReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P1 fix review#10/#11: tests for the exact-alarm-permission receiver and the
 * timezone/time-change receiver (recompute from timeLabel+date, wall clock).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimeChangeReceiverTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository
    private lateinit var store: AlarmStore
    private lateinit var alarmManager: AlarmManager

    // Fixed clock: Mon 2026-11-23 06:00 Europe/Amsterdam
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("calendar_alarm_store", "AlarmRepositoryState", "calalarm_widget_cache",
            "notif_funnel", "alarm_settings", "alarm_service_state").forEach {
            DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        DeviceProtectedStorage.prefs(context, "calalarm_dp_migration").edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        repo = AlarmRepository(context)
        store = repo.store
        FixedClock.set(ZonedDateTime.of(2026, 11, 23, 6, 0, 0, 0, amsterdam))
    }

    @After
    fun tearDown() {
        FixedClock.reset()
        store.removeAll()
    }

    private fun epochOf(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    private fun alarmClockTrigger(): Long? =
        shadowOf(alarmManager).scheduledAlarms.firstOrNull { it.alarmClockInfo != null }?.triggerAtTime

    // ============== #11: TIMEZONE_CHANGED recomputes from wall clock ==============

    @Test
    fun `p1 timezone switch recomputes one-shot from date+timeLabel in the new zone`() {
        // Alarm tomorrow 2026-11-24 07:00 Amsterdam (CET, UTC+1) = 06:00 UTC
        val triggerAmsterdam = epochOf(LocalDate.of(2026, 11, 24), LocalTime.of(7, 0), amsterdam)
        val alarm = AlarmStore.OwnedAlarm(
            id = store.nextId(),
            triggerAtMillis = triggerAmsterdam,
            timeLabel = "07:00",
            label = "TZ-test",
            date = "2026-11-24",
            type = "manual",
            enabled = true
        )
        repo.schedule(alarm)
        assertEquals(triggerAmsterdam, alarmClockTrigger())

        // Switch timezone to New York (UTC-5 in November) — same wall clock 07:00 = 12:00 UTC
        val newYork = ZoneId.of("America/New_York")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(newYork))
        try {
            TimeChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

            val expectedNY = epochOf(LocalDate.of(2026, 11, 24), LocalTime.of(7, 0), newYork)
            val after = store.get(alarm.id)
            assertNotNull(after)
            assertEquals("P1 #11: trigger must be recomputed to 07:00 in the NEW zone", expectedNY, after!!.triggerAtMillis)
            assertEquals(expectedNY, alarmClockTrigger())
        } finally {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(amsterdam))
        }
    }

    @Test
    fun `p1 time set triggers rescheduleAll without crash`() {
        val trigger = epochOf(LocalDate.of(2026, 11, 24), LocalTime.of(7, 0), amsterdam)
        val alarm = AlarmStore.OwnedAlarm(
            id = store.nextId(), triggerAtMillis = trigger, timeLabel = "07:00",
            label = "T", date = "2026-11-24", type = "manual", enabled = true
        )
        repo.schedule(alarm)
        alarmManager.cancelAll() // simulate: AlarmManager empty (e.g. after reboot)

        TimeChangeReceiver().onReceive(context, Intent("android.intent.action.TIME_SET"))

        assertEquals("P1 #11: TIME_SET must reschedule the alarm", trigger, alarmClockTrigger())
    }

    // ============== N-d (review): TZ switch also recomputes future repeat alarms ==============

    @Test
    fun `n-d timezone switch recomputes future repeat alarm via computeNextTrigger (07h00 stays local)`() {
        // Now = Mon 2026-11-23 06:00 Amsterdam. Repeat alarm Mon-Fri 07:00 with trigger
        // TODAY 07:00 Amsterdam (CET) = 2026-11-23T06:00:00Z — still in the future.
        val triggerAmsterdam = epochOf(LocalDate.of(2026, 11, 23), LocalTime.of(7, 0), amsterdam)
        val alarm = AlarmStore.OwnedAlarm(
            id = store.nextId(),
            triggerAtMillis = triggerAmsterdam,
            timeLabel = "07:00",
            label = "Repeat-TZ",
            date = "2026-11-23",
            type = "office",
            enabled = true,
            repeatDays = listOf(1, 2, 3, 4, 5)
        )
        repo.schedule(alarm)
        assertEquals(triggerAmsterdam, alarmClockTrigger())

        // Switch Amsterdam (UTC+1) → London (UTC+0 in November): same wall clock 07:00
        // must become 07:00 LONDON = 2026-11-23T07:00:00Z (one hour later in absolute time,
        // but still 07:00 on the wall clock — NOT the old epoch 06:00Z).
        val london = ZoneId.of("Europe/London")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(london))
        try {
            TimeChangeReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

            val expectedLondon = epochOf(LocalDate.of(2026, 11, 23), LocalTime.of(7, 0), london)
            assertEquals("expected: 07:00 London = 2026-11-23T07:00:00Z", Instant.parse("2026-11-23T07:00:00Z").toEpochMilli(), expectedLondon)
            val after = store.get(alarm.id)
            assertNotNull(after)
            assertEquals("N-d: repeat alarm stays 07:00 LOCAL in the new zone", expectedLondon, after!!.triggerAtMillis)
            assertEquals(expectedLondon, alarmClockTrigger())
            // Exactly 1 enabled record (no duplicates)
            assertEquals(1, store.getAll().count { it.enabled && !it.isSnooze })
        } finally {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(amsterdam))
        }
    }

    // ============== #10: exact-alarm permission changed → rescheduleAll ==============

    @Test
    fun `p1 exact alarm permission receiver reschedules all alarms`() {
        val trigger = epochOf(LocalDate.of(2026, 11, 24), LocalTime.of(7, 0), amsterdam)
        val alarm = AlarmStore.OwnedAlarm(
            id = store.nextId(), triggerAtMillis = trigger, timeLabel = "07:00",
            label = "P", date = "2026-11-24", type = "manual", enabled = true
        )
        repo.schedule(alarm)
        alarmManager.cancelAll() // simulate: everything cancelled

        ExactAlarmPermissionReceiver().onReceive(
            context,
            Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        )

        assertEquals("P1 #10: after the permission change the alarm must be rescheduled", trigger, alarmClockTrigger())
    }

    // Re-review blocker 1: TIME_SET must not undo a "Skip this time"
    @Test
    fun `re-review TIME_SET after skip keeps the skipped repeat alarm on the follow-up date`() {
        // now = Mon 23-11 06:00; Mon-Fri 07:00 was skipped today → record is on Tue 24-11 07:00
        val tue = epochOf(LocalDate.of(2026, 11, 24), LocalTime.of(7, 0), amsterdam)
        val alarm = AlarmStore.OwnedAlarm(
            id = store.nextId(), triggerAtMillis = tue, timeLabel = "07:00", label = "Werk",
            date = "2026-11-24", type = "office", enabled = true,
            originalTriggerMillis = tue, repeatDays = listOf(1, 2, 3, 4, 5)
        )
        repo.schedule(alarm, force = true)

        io.github.calendaralarm.receiver.TimeChangeReceiver()
            .onReceive(context, android.content.Intent(android.content.Intent.ACTION_TIME_CHANGED))

        val after = store.get(alarm.id)!!
        assertEquals("skipped alarm stays on Tue 24-11 07:00", tue, after.triggerAtMillis)
        assertEquals("2026-11-24", after.date)
    }
}
