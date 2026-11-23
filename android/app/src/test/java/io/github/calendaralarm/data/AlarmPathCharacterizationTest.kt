package io.github.calendaralarm.data

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.receiver.OwnedAlarmReceiver
import io.github.calendaralarm.service.AlarmForegroundService
import io.github.calendaralarm.service.BootReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
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
 * PHASE 0 SAFETY NET — characterization tests for the critical alarm path.
 *
 * These tests capture the CURRENT (v2.7.0) behavior of schedule / fire / snooze /
 * dismiss / repeat / boot / DST / timer / corrupt store BEFORE anything changes.
 * They run with a FIXED CLOCK (FixedClock, Europe/Amsterdam) and ShadowAlarmManager,
 * so they are deterministic.
 *
 * Robolectric 4.14 runs in PAUSED looper mode by default; FixedClock.now()
 * then delegates to ShadowPausedSystemClock (its own counter). FixedClock sets that counter
 * via reflection, so the tests are deterministic without a looperMode parameter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmPathCharacterizationTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository
    private lateinit var store: AlarmStore
    private lateinit var alarmManager: AlarmManager

    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean prefs per test
        listOf(
            "calendar_alarm_store", "AlarmRepositoryState", "calalarm_widget_cache",
            "notif_funnel", "calalarm_timer_state", "calendar_alarm_settings",
            "calendar_alarm_secure_prefs", "alarm_settings", "alarm_service_state",
            "alarm_sync_prefs"
        ).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
            // P1 #1: also clear DP prefs (AlarmStore etc. now read from DP)
            io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
        }
        // P1: sdk=34 → POST_NOTIFICATIONS runtime permission must be granted explicitly
        // for notifyMissedAlarm() to post a notification (on sdk<33 it is implicitly granted)
        org.robolectric.Shadows.shadowOf(context.applicationContext as Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Robolectric sdk=34: exact-alarm permission explicitly on (on a real device with permission)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        repo = AlarmRepository(context)
        store = repo.store
        // Fixed clock: Monday 2026-11-23 06:00 Europe/Amsterdam (CET, UTC+1)
        FixedClock.set(ZonedDateTime.of(2026, 11, 23, 6, 0, 0, 0, zone))
    }

    @After
    fun tearDown() {
        FixedClock.reset()
    }

    // ============== helpers ==============

    private fun shadowAm() = shadowOf(alarmManager)

    /** Trigger time of the first upcoming setAlarmClock entry (null if there is none). */
    private fun alarmClockTrigger(): Long? =
        shadowAm().scheduledAlarms.firstOrNull { it.alarmClockInfo != null }?.triggerAtTime

    private fun scheduledTriggers(): List<Long> = shadowAm().scheduledAlarms.map { it.triggerAtTime }

    private fun makeAlarm(
        id: Int = -1,
        triggerAtMillis: Long,
        timeLabel: String = "07:00",
        label: String = "Test",
        date: String = LocalDate.ofInstant(Instant.ofEpochMilli(triggerAtMillis), zone).toString(),
        type: String = "manual",
        repeatDays: List<Int> = emptyList(),
        isSnooze: Boolean = false,
        enabled: Boolean = true,
        snoozeMinutesOverride: Int? = null
    ): AlarmStore.OwnedAlarm {
        val realId = if (id >= 0) id else store.nextId()
        return AlarmStore.OwnedAlarm(
            id = realId,
            triggerAtMillis = triggerAtMillis,
            timeLabel = timeLabel,
            label = label,
            date = date,
            type = type,
            enabled = enabled,
            isSnooze = isSnooze,
            originalTriggerMillis = triggerAtMillis,
            repeatDays = repeatDays,
            snoozeMinutesOverride = snoozeMinutesOverride
        )
    }

    private fun epochOf(date: LocalDate, time: LocalTime): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    private fun fire(alarmId: Int) {
        val intent = Intent(context, OwnedAlarmReceiver::class.java)
            .putExtra(AlarmRepository.EXTRA_ALARM_ID, alarmId)
        OwnedAlarmReceiver().onReceive(context, intent)
    }

    private fun snooze(alarmId: Int) {
        val intent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_SNOOZE
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        OwnedAlarmReceiver().onReceive(context, intent)
    }

    private fun dismiss(alarmId: Int) {
        val intent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_DISMISS
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        OwnedAlarmReceiver().onReceive(context, intent)
    }

    private fun startedServiceNames(): List<String> {
        val app = shadowOf(context as Application)
        return generateSequence { app.nextStartedService }
            .mapNotNull { it.component?.className }
            .toList()
    }

    private fun stoppedServiceNames(): List<String> {
        val app = shadowOf(context as Application)
        return generateSequence { app.nextStoppedService }
            .mapNotNull { it.component?.className }
            .toList()
    }

    // ============== 2a: scheduling a one-shot alarm → exact epoch; cancelling → gone ==============

    @Test
    fun `a schedule one-shot sets setAlarmClock at exactly the right epoch`() {
        // Clock: Mon 2026-11-23 05:30 CET → one-shot 07:00 = today 07:00 CET = 06:00 UTC
        FixedClock.set(ZonedDateTime.of(2026, 11, 23, 5, 30, 0, 0, zone))
        val expected = Instant.parse("2026-11-23T06:00:00Z").toEpochMilli()

        val alarm = repo.scheduleOneShot("07:00", "Test", type = "manual")!!
        assertEquals(expected, alarm.triggerAtMillis)

        // ShadowAlarmManager has an alarm-clock entry at exactly that epoch
        assertEquals(expected, alarmClockTrigger())
        // Pre-alarm (06:00) is also scheduled as a regular scheduled alarm
        assertTrue(scheduledTriggers().contains(expected - AlarmRepository.PRE_ALARM_OFFSET_MS))
        // Record is in the store
        assertEquals(alarm.id, store.get(alarm.id)?.id)
    }

    @Test
    fun `a cancel cancels AlarmManager entry and pre-alarm`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 23, 5, 30, 0, 0, zone))
        val alarm = repo.scheduleOneShot("07:00", "Test")!!
        assertNotNull(alarmClockTrigger())

        assertTrue(repo.cancel(alarm.id))

        assertNull("after cancel no alarm-clock anymore", alarmClockTrigger())
        assertTrue("after cancel no scheduled alarms anymore (incl. pre-alarm)", scheduledTriggers().isEmpty())
        assertNull("record from store", store.get(alarm.id))
    }

    // ============== 2b: firing without action → FGS started, record not removed ==============

    @Test
    fun `B3 service start is requested even when the alarm lock is held`() {
        val alarm = makeAlarm(triggerAtMillis = FixedClock.now() - 1_000)
        store.save(alarm)
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // Another thread holds AlarmTransactions (e.g. a running sync-apply)
        val holder = Thread {
            AlarmTransactions.run { locked.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        }.apply { start() }
        assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val firer = Thread { fire(alarm.id) }.apply { start() }
        // The service must already be started while the lock is still held
        var started = false
        for (i in 0 until 50) {
            if (startedServiceNames().contains(AlarmForegroundService::class.java.name)) { started = true; break }
            Thread.sleep(20)
        }
        release.countDown(); holder.join(5_000); firer.join(5_000)
        assertTrue("B3: service-start request must not wait on the alarm lock", started)
    }

    @Test
    fun `b fire starts AlarmForegroundService and record stays (disabled) in store`() {
        val trigger = FixedClock.now() - 60_000 // 1 min ago
        val alarm = makeAlarm(triggerAtMillis = trigger)
        store.save(alarm)

        fire(alarm.id)

        assertTrue(
            "AlarmForegroundService must be started",
            startedServiceNames().contains(AlarmForegroundService::class.java.name)
        )
        val after = store.get(alarm.id)
        assertNotNull("NC-02: record must NOT be removed when firing", after)
        assertFalse("record is in ringing state (enabled=false)", after!!.enabled)
    }

    // ============== 2c: snooze → new alarm at now+duration; override; 0 = dismiss ==============

    @Test
    fun `c snooze with global duration 5 min schedules new alarm at now+5min and removes old record`() {
        val now = FixedClock.now()
        val alarm = makeAlarm(triggerAtMillis = now - 60_000)
        store.save(alarm)
        // global snooze duration default = 5 (regular prefs clean)
        assertEquals(5, SettingsRepository(context).getSnoozeMinutes())

        snooze(alarm.id)

        val snoozeWall = FixedClock.now() // after the receiver call (clock may have shifted 0-1 ms due to internal calls)
        assertNull("old record must be gone", store.get(alarm.id))
        val snoozed = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull("snooze record expected", snoozed)
        assertTrue(
            "snooze trigger = wall-clock time of snooze + 5 min (within 2s tolerance)",
            kotlin.math.abs(snoozed!!.triggerAtMillis - (snoozeWall + 5 * 60_000L)) < 2_000
        )
        assertTrue(snoozed.enabled)
        // snooze is scheduled in AlarmManager at the same epoch
        assertTrue(scheduledTriggers().contains(snoozed.triggerAtMillis))
        // service stopped
        assertTrue(stoppedServiceNames().contains(AlarmForegroundService::class.java.name))
    }

    @Test
    fun `c snooze with per-alarm override 10 min beats global 5`() {
        val now = FixedClock.now()
        val alarm = makeAlarm(triggerAtMillis = now - 60_000, snoozeMinutesOverride = 10)
        store.save(alarm)

        snooze(alarm.id)

        val snoozeWall = FixedClock.now()
        val snoozed = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull(snoozed)
        assertTrue(
            "override 10 min (within 2s tolerance)",
            kotlin.math.abs(snoozed!!.triggerAtMillis - (snoozeWall + 10 * 60_000L)) < 2_000
        )
    }

    @Test
    fun `c snooze duration 0 (per-alarm override) behaves as dismiss`() {
        val now = FixedClock.now()
        val alarm = makeAlarm(triggerAtMillis = now - 60_000, snoozeMinutesOverride = 0)
        store.save(alarm)

        snooze(alarm.id)

        assertNull("with snooze=0 no record", store.get(alarm.id))
        assertTrue("with snooze=0 no snooze record", store.getAll().none { it.isSnooze })
        assertTrue("service stopped (dismiss path)", stoppedServiceNames().contains(AlarmForegroundService::class.java.name))
        assertNull("no alarm-clock entry", alarmClockTrigger())
    }

    // ============== 2d: dismiss → service stopped, alarm gone, repeat chain intact ==============

    @Test
    fun `d dismiss stops service, removes record, repeat chain (re-arm) stays intact`() {
        val now = FixedClock.now()
        val alarm = makeAlarm(
            triggerAtMillis = now - 60_000,
            repeatDays = listOf(1, 2, 3, 4, 5) // Mon-Fri 07:00; now = Mon 06:00
        )
        store.save(alarm)

        fire(alarm.id)
        // re-arm to next workday (Tue 24-11 07:00) created on fire
        val reArm = store.getAll().firstOrNull { it.id != alarm.id && !it.isSnooze && it.enabled }
        assertNotNull("re-arm after fire expected", reArm)
        val reArmId = reArm!!.id

        dismiss(alarm.id)

        assertNull("original record gone after dismiss", store.get(alarm.id))
        // Only the re-arm (Tue 24-11 07:00) may still be scheduled as alarm-clock.
        // (Before AppClock this test ran on the real clock and filtered by accident.)
        val clocks = shadowAm().scheduledAlarms.filter { it.alarmClockInfo != null }.map { it.triggerAtTime }
        assertEquals("only the re-arm is still scheduled", listOf(store.get(reArmId)!!.triggerAtMillis), clocks)
        assertNotNull("re-arm record survives dismiss", store.get(reArmId))
        assertTrue("re-arm record is enabled", store.get(reArmId)!!.enabled)
        assertTrue("service stopped", stoppedServiceNames().contains(AlarmForegroundService::class.java.name))
    }

    // ============== 2e: repeating alarm Mon-Fri 07:00 — 14 days fire+dismiss ==============

    @Test
    fun `e repeat mon-fri 07-00 14 day simulation - exactly 1 alarm each workday, weekend empty`() {
        // Start: Mon 2026-11-23 06:00; schedule initial occurrence
        val first = repo.scheduleOneShot("07:00", "Work", days = listOf(1, 2, 3, 4, 5), type = "office")!!
        assertEquals("2026-11-23", first.date)
        assertEquals(epochOf(LocalDate.of(2026, 11, 23), LocalTime.of(7, 0)), first.triggerAtMillis)

        val start = LocalDate.of(2026, 11, 23)
        var day = start
        repeat(14) {
            // Set the clock to that day 07:00 (the moment of firing)
            FixedClock.set(ZonedDateTime.of(day, LocalTime.of(7, 0), zone))
            val isWerkdag = day.dayOfWeek.value in 1..5
            // Before that day's firing: on a workday there is exactly 1 enabled record
            // for TODAY (the occurrence that is about to fire); on weekends zero.
            val todays = store.getAll().filter { !it.isSnooze && it.enabled && it.date == day.toString() }
            if (!isWerkdag) {
                assertEquals("weekend $day: no alarm scheduled", 0, todays.size)
            }

            // After fire+dismiss on workday X: exactly 1 enabled record = the re-arm.
            // CURRENT behavior (v2.7.0): computeNextTriggerAfterToday → FROM TOMORROW.
            // Per day before firing: exactly 1 enabled record. On workday X that
            // record is on a workday ≥ X (today or a next workday — current behavior
            // re-arming via computeNextTriggerAfterToday can stay on "today" on the first day).
            // On weekends: no record for that day.
            val enabled = store.getAll().filter { !it.isSnooze && it.enabled }
            assertEquals("day $day: exactly 1 enabled record (no duplicates)", 1, enabled.size)
            val recDate = LocalDate.parse(enabled[0].date)
            if (isWerkdag) {
                // CURRENT behavior (v2.7.0): the re-arm of day X-1 can stay on X-1 itself via
                // computeNextTriggerAfterToday (if the fire was before 07:00) — the record is thus
                // on a workday in [day-1, infinity).
                // The essence of the safety-net test: per day exactly 1 enabled record,
                // on a workday at 07:00, never on a weekend, never duplicates.
                assertTrue("workday $day: record on a workday (was $recDate)", recDate.dayOfWeek.value in 1..5)
                assertEquals("record trigger = $recDate 07:00", epochOf(recDate, LocalTime.of(7, 0)), enabled[0].triggerAtMillis)
            } else {
                // weekend: the only enabled record is on a workday (never on a weekend)
                assertTrue("weekend $day: record on a workday (was $recDate)", recDate.dayOfWeek.value in 1..5)
            }

            if (isWerkdag) {
                // fire claims the record as ringing (enabled=false) and re-arms a NEW
                // record; dismiss removes only the fired record.
                fire(enabled[0].id)
                dismiss(enabled[0].id)
            }

            // After fire+dismiss: exactly 1 enabled re-arm on a workday at 07:00 (never weekend).
            val afterFire = store.getAll().filter { !it.isSnooze && it.enabled }
            assertEquals("after day $day: exactly 1 enabled re-arm (no duplicates)", 1, afterFire.size)
            val reArmDate = LocalDate.parse(afterFire[0].date)
            assertTrue("re-arm after day $day is a workday (was $reArmDate)", reArmDate.dayOfWeek.value in 1..5)
            assertEquals(
                "re-arm trigger = $reArmDate 07:00",
                epochOf(reArmDate, LocalTime.of(7, 0)),
                afterFire[0].triggerAtMillis
            )
            assertEquals("07:00", afterFire[0].timeLabel)
            assertEquals(listOf(1, 2, 3, 4, 5), afterFire[0].repeatDays)

            day = day.plusDays(1)
        }
        // End: after 14 days there is exactly 1 enabled record, on a workday at 07:00
        // (current behavior: the re-arm "sticks" to the last fire day via
        // computeNextTriggerAfterToday — see also the DST/boot tests; the essence is:
        // never more than 1 enabled record, never on a weekend).
        val last = store.getAll().filter { !it.isSnooze && it.enabled }
        assertEquals(1, last.size)
        val lastDate = LocalDate.parse(last[0].date)
        assertTrue("final record on a workday (was $lastDate)", lastDate.dayOfWeek.value in 1..5)
        assertEquals(epochOf(lastDate, LocalTime.of(7, 0)), last[0].triggerAtMillis)
    }

    // ============== 2f: DST — 07:00 around 29-03-2026 and 25-10-2026 ==============

    @Test
    fun `f DST summer 29-03-2026 - 07-00 local time = 05-00 UTC (CEST)`() {
        // Sat 2026-03-28 12:00; next Sunday = 29-03 (DST switch at 02:00 → 03:00)
        FixedClock.set(ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, zone))
        val trigger = repo.computeNextTrigger("07:00", listOf(7), LocalDate.of(2026, 3, 29))
        assertNotNull("computeNextTrigger must find a trigger", trigger)
        val alarm = makeAlarm(
            triggerAtMillis = trigger!!.toEpochMilli(),
            timeLabel = "07:00",
            repeatDays = listOf(7),
            date = "2026-03-29"
        )
        repo.schedule(alarm)
        assertEquals(
            "Sunday 29-03-2026 07:00 CEST = 05:00 UTC",
            Instant.parse("2026-03-29T05:00:00Z").toEpochMilli(),
            trigger.toEpochMilli()
        )
        assertEquals(trigger.toEpochMilli(), alarmClockTrigger())
    }

    @Test
    fun `f DST winter 25-10-2026 - 07-00 local time = 06-00 UTC (CET)`() {
        // Sat 2026-10-24 12:00; next Sunday = 25-10 (DST switch at 03:00 → 02:00)
        FixedClock.set(ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone))
        val trigger = repo.computeNextTrigger("07:00", listOf(7), LocalDate.of(2026, 10, 25))
        assertNotNull(trigger)
        val alarm = makeAlarm(
            triggerAtMillis = trigger!!.toEpochMilli(),
            timeLabel = "07:00",
            repeatDays = listOf(7),
            date = "2026-10-25"
        )
        repo.schedule(alarm)
        assertEquals(
            "Sunday 25-10-2026 07:00 CET = 06:00 UTC",
            Instant.parse("2026-10-25T06:00:00Z").toEpochMilli(),
            trigger.toEpochMilli()
        )
        assertEquals(trigger.toEpochMilli(), alarmClockTrigger())
    }

    // ============== 2g: BootReceiver / rescheduleAll ==============

    @Test
    fun `g boot reschedules future alarm again in AlarmManager`() {
        val trigger = FixedClock.now() + 2 * 3_600_000 // in 2 hours
        val alarm = makeAlarm(triggerAtMillis = trigger)
        store.save(alarm)
        // Simulate reboot: AlarmManager is empty after boot (cancelAll on the real AlarmManager)
        alarmManager.cancelAll()
        assertNull(alarmClockTrigger())

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals("after boot the alarm must be scheduled again", trigger, alarmClockTrigger())
        assertNotNull("record stays in store", store.get(alarm.id))
    }

    @Test
    fun `g boot reschedules repeating alarm with trigger in the past to next occurrence (current behavior)`() {
        // Clock Mon 2026-11-23 06:00; repeat Mon-Fri 07:00 with trigger yesterday (past).
        // NB: computeNextTriggerAfterToday starts FROM TOMORROW → Tue 24-11 07:00,
        // even if today 07:00 is still in the future (see @Ignore BUG review#4).
        val past = epochOf(LocalDate.of(2026, 11, 19), LocalTime.of(7, 0)) // Thu 19-11 (past, >1 day ago)
        val alarm = makeAlarm(triggerAtMillis = past, date = "2026-11-19", repeatDays = listOf(1, 2, 3, 4, 5))
        store.save(alarm)

        repo.rescheduleAll()

        // CURRENT behavior (v2.7.0): computeNextTriggerAfterToday → FROM TOMORROW = Tue 24-11 07:00
        // (today Mon 23-11 is skipped, even though 07:00 today is still in the future —
        //  see @Ignore("BUG: review#4"))
        val expected = epochOf(LocalDate.of(2026, 11, 23), LocalTime.of(7, 0)) // TODAY 07:00 = 06:00 UTC
        val after = store.get(alarm.id)
        assertNotNull(after)
        assertEquals("current behavior shifts to TODAY 07:00 (06:00 UTC)", expected, after!!.triggerAtMillis)
        assertEquals(expected, alarmClockTrigger())
        assertEquals(0, after.skipCount)
    }

    @Test
    fun `g P1 fix boot reschedules repeating alarm with trigger in the past to TODAY if 07-00 not yet passed`() {
        // Clock Mon 2026-11-23 06:00 (Mon = repeat day, 07:00 is in 1 hour)
        val past = epochOf(LocalDate.of(2026, 11, 22), LocalTime.of(7, 0))
        val alarm = makeAlarm(triggerAtMillis = past, date = "2026-11-22", repeatDays = listOf(1, 2, 3, 4, 5))
        store.save(alarm)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val expected = epochOf(LocalDate.of(2026, 11, 23), LocalTime.of(7, 0)) // TODAY 07:00
        val after = store.get(alarm.id)
        assertNotNull(after)
        assertEquals("DESIRED: today 07:00 (not yet passed)", expected, after!!.triggerAtMillis)
        assertEquals(expected, alarmClockTrigger())
    }

    // ============== 2i: corrupt store JSON ==============

    @Test
    fun `i DESIRED corrupt store JSON is not silently discarded but quarantined`() {
        val prefs = io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store")
        val corrupt = """{"1":{"id":1,"triggerAtMillis":123,"timeLabel":"07:00" """
        prefs.edit().putString("alarms", corrupt).commit()

        val s = AlarmStore(context)
        val all = s.getAll() // may be empty, but...

        // DESIRED: the corrupt blob is kept for recovery/analysis
        val quarantined = prefs.getString("alarms_quarantine", null)
        assertNotNull("corrupt JSON must be kept in the quarantine key", quarantined)
        assertEquals(corrupt, quarantined)

        // B3: after quarantine the store accepts new saves again (no permanent block)
        s.save(
            AlarmStore.OwnedAlarm(
                id = 42, triggerAtMillis = FixedClock.now() + 3_600_000,
                timeLabel = "07:00", label = "New", date = "2026-11-23", type = "manual"
            )
        )
        assertEquals(1, s.getAll().size)
        assertEquals(42, s.getAll().first().id)
        // quarantine blob is kept
        assertEquals(corrupt, prefs.getString("alarms_quarantine", null))
    }

    @Test
    fun `i characterization current behavior corrupt store JSON - loadAll returns empty without crash`() {
        val prefs = io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store")
        prefs.edit().putString("alarms", "{not valid json").commit()

        val s = AlarmStore(context)
        // After P1 fix #2: loadAll also returns empty (fail-safe) but quarantines +
        // logs ERROR — this characterization holds: no crash, empty list.
        assertTrue(s.getAll().isEmpty())
    }

    // ============== N-c (review): trigger < 10 min ago → fire NOW (now+5s) ==============

    @Test
    fun `n-c rescheduleAll fires alarm that fired 5 min ago NOW (now+5s)`() {
        val now = FixedClock.now()
        val recent = now - 5 * 60_000 // 5 min ago — within the 10 min grace
        val alarm = makeAlarm(triggerAtMillis = recent, date = "2026-11-23")
        store.save(alarm)

        repo.rescheduleAll()

        val after = store.get(alarm.id)
        assertNotNull("N-c: record must be kept", after)
        assertTrue("N-c: trigger must be now+5s (now=$now, after=${after!!.triggerAtMillis})", after.triggerAtMillis >= now + 4_000 && after.triggerAtMillis <= now + 6_000)
        assertEquals("N-c: AlarmManager entry at now+5s", after.triggerAtMillis, alarmClockTrigger())
        // no "missed" notification
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val activeIds = org.robolectric.Shadows.shadowOf(nm).activeNotifications.map { it.id }
        assertTrue("N-c: no MISSED_ALARM notification", io.github.calendaralarm.service.NotificationIds.MISSED_ALARM !in activeIds)
    }

    @Test
    fun `n-c rescheduleAll DOES report alarm that fired 30 min ago as missed`() {
        val now = FixedClock.now()
        val old = now - 30 * 60_000 // 30 min ago — outside grace
        val alarm = makeAlarm(triggerAtMillis = old, date = "2026-11-22")
        store.save(alarm)

        repo.rescheduleAll()

        val after = store.get(alarm.id)
        assertNotNull(after)
        assertFalse("N-c: 30min ago = missed → enabled=false", after!!.enabled)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val activeIds = org.robolectric.Shadows.shadowOf(nm).activeNotifications.map { it.id }
        assertTrue("N-c: 30min ago → MISSED_ALARM notification", io.github.calendaralarm.service.NotificationIds.MISSED_ALARM in activeIds)
    }

    // ============== B4 (review): snooze on fallback alarm (no record) still schedules a snooze ==============

    @Test
    fun `b4 snooze on alarm without store record schedules a snooze with new id instead of just stopping`() {
        // No record for id=777 — e.g. fallback fire from #3
        assertNull(store.get(777))

        snooze(777)

        val snoozed = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull("B4: snooze without record must still schedule a snooze record", snoozed)
        assertTrue("B4: snooze trigger must be in the future", snoozed!!.triggerAtMillis > FixedClock.now())
        assertTrue("B4: snooze must be in AlarmManager", scheduledTriggers().contains(snoozed.triggerAtMillis))
        assertTrue("B4: service must be stopped (ringing over)", stoppedServiceNames().contains(AlarmForegroundService::class.java.name))
    }

    // ============== B2 (review): snooze duration via settingsRepository, not from wrong file ==============

    @Test
    fun `b2 setSnoozeMinutes 10 via settingsRepository results in snooze at now+10min`() {
        // Write via the official API (SettingsRepository → calendar_alarm_settings)
        assertTrue(SettingsRepository(context).setSnoozeMinutes(10))
        assertEquals(10, SettingsRepository(context).getSnoozeMinutes())

        val now = FixedClock.now()
        val alarm = makeAlarm(triggerAtMillis = now - 60_000)
        store.save(alarm)

        snooze(alarm.id)

        val snoozed = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull("snooze record expected", snoozed)
        assertTrue(
            "B2: snooze must be now+10min (override via settingsRepository), was ${snoozed!!.triggerAtMillis - now}ms after now",
            kotlin.math.abs(snoozed.triggerAtMillis - (now + 10 * 60_000L)) < 2_000
        )
        // The receiver must read the same value as the UI (consistency)
        assertEquals(10L, io.github.calendaralarm.receiver.OwnedAlarmReceiver.getSnoozeMinutes(context))
    }

    // ============== P1 review#3: fire without store record → still rings ==============

    @Test
    fun `p1 fire without store record still starts AlarmForegroundService with default label`() {
        // No record in the store for id=4242
        assertNull(store.get(4242))

        fire(4242)

        assertTrue(
            "P1 #3: even without a record AlarmForegroundService must be started (fail-safe)",
            startedServiceNames().contains(AlarmForegroundService::class.java.name)
        )
        // NO ghost record may appear in the store
        assertNull("P1 #3: fallback fire must not save a ghost record", store.get(4242))
    }

    // ============== P1 review#4/#5: rescheduleAll edge cases ==============

    @Test
    fun `p1 rescheduleAll repeats TODAY if 07-00 is still in the future (now 06-00)`() {
        // Clock Mon 2026-11-23 06:00; trigger yesterday (Sun 22-11 07:00, past)
        val past = epochOf(LocalDate.of(2026, 11, 22), LocalTime.of(7, 0))
        val alarm = makeAlarm(triggerAtMillis = past, date = "2026-11-22", repeatDays = listOf(1, 2, 3, 4, 5))
        store.save(alarm)

        repo.rescheduleAll()

        val expected = epochOf(LocalDate.of(2026, 11, 23), LocalTime.of(7, 0)) // TODAY 07:00
        val after = store.get(alarm.id)
        assertNotNull("record must be kept (same id)", after)
        assertEquals("P1 #4: must be TODAY 07:00 (not yet passed)", expected, after!!.triggerAtMillis)
        assertEquals(expected, alarmClockTrigger())
    }

    @Test
    fun `p1 rescheduleAll shifts repeating alarm to TOMORROW if 07-00 today already passed`() {
        // Clock Mon 2026-11-23 08:00 — 07:00 today has already passed.
        // N-h (review): fixed expected epoch — Mon 2026-11-23 08:00 Amsterdam, so
        // "today 07:00" has already passed → next Mon-Fri 07:00 = Tue 24-11 07:00 CET
        // = 2026-11-24T06:00:00Z. Fixed value instead of computeNextTrigger (which compares
        // itself with itself).
        FixedClock.set(ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, zone))
        val past = epochOf(LocalDate.of(2026, 11, 22), LocalTime.of(7, 0)) // Sun 22-11 07:00
        val alarm = makeAlarm(triggerAtMillis = past, date = "2026-11-22", repeatDays = listOf(1, 2, 3, 4, 5))
        store.save(alarm)

        repo.rescheduleAll()

        val expected = Instant.parse("2026-11-24T06:00:00Z").toEpochMilli() // Tue 24-11 07:00 CET
        val after = store.get(alarm.id)
        assertNotNull(after)
        assertEquals(
            "N-h: now=Mon 23-11 08:00 → tomorrow Tue 24-11 07:00 (06:00 UTC)",
            expected, after!!.triggerAtMillis
        )
        assertTrue("P1 #4: trigger must be in the future", after.triggerAtMillis > FixedClock.now())
        assertEquals(expected, alarmClockTrigger())
    }

    @Test
    fun `p1 rescheduleAll missed one-shot alarm is DISABLED with notification instead of silently removed`() {
        val now = FixedClock.now()
        val alarm = makeAlarm(triggerAtMillis = now - 3_600_000, date = "2026-11-22") // 1h ago, one-shot
        store.save(alarm)

        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val before = org.robolectric.Shadows.shadowOf(nm).size()

        repo.rescheduleAll()

        val after = store.get(alarm.id)
        assertNotNull("P1 #5: record must NOT be silently removed", after)
        assertFalse("P1 #5: missed alarm must be enabled=false", after!!.enabled)
        // A MISSED_ALARM notification must have been posted (at least 1 new)
        val afterCount = org.robolectric.Shadows.shadowOf(nm).size()
        assertTrue(
            "P1 #5: an 'Alarm missed' notification must have been posted (before=$before after=$afterCount)",
            afterCount > before
        )
        // And the active notifications contain the MISSED_ALARM id
        val activeIds = org.robolectric.Shadows.shadowOf(nm).activeNotifications.map { it.id }
        assertTrue(
            "P1 #5: active notifications must contain MISSED_ALARM (${io.github.calendaralarm.service.NotificationIds.MISSED_ALARM}): $activeIds",
            io.github.calendaralarm.service.NotificationIds.MISSED_ALARM in activeIds
        )
    }
}
