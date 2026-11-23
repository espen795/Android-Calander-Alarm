package io.github.calendaralarm.worker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.DeviceProtectedStorage
import io.github.calendaralarm.data.FixedClock
import io.github.calendaralarm.sync.SyncRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P2A B1+B2 (Opus review): end-to-end tests of AlarmSyncWorker.doWork() with a
 * FAKE fetcher (no network) — the reviewer could not catch mutations in doWork
 * earlier because only the pure functions were tested.
 *
 * Scenarios:
 *  B2: invalid plan → nothing overwritten + Result.retry
 *  B2: date mismatch → nothing overwritten + retry
 *  B2: today's wake time already passed → skip without error
 *  B2: identical plan 2 days → 0 notifications (SET/CHANGED)
 *  B2: "off" vs existing alarm tomorrow → REMOVED notification + record gone
 *  B2: counter reset after success
 *  B1a: today disabled (no record) → sync creates nothing
 *  B1b: alarm today already fired+dismissed → later plan today → no new alarm
 *  B1c: snooze running + server says off for today → snooze stays, no REMOVED
 *  B1d: pending alarm today 08:00, plan 07:45 → gets updated (CHANGED)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])  // SDK<31 → canScheduleExactAlarms() is always true; no shadow needed
class AlarmSyncWorkerDoWorkTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 11, 22)   // Sunday
    private val tomorrow = LocalDate.of(2026, 11, 23) // Monday

    /** Fake fetcher: per date a plan (or failure) — no network. */
    private class FakeFetcher : AlarmPlanFetcher {
        val plans = mutableMapOf<String, SyncRepository.SyncResult>()
        val requestedDates = mutableListOf<String>()
        override suspend fun fetchAlarmPlan(date: String): SyncRepository.SyncResult {
            requestedDates.add(date)
            return plans[date] ?: SyncRepository.SyncResult(false, error = "no plan for $date")
        }
        override fun close() {}
    }

    private fun plan(date: String, wakeTime: String?, label: String = "Kantoor", loc: String = "office") =
        SyncRepository.SyncResult(
            true,
            SyncRepository.AlarmPlan(date, wakeTime, label, loc, "high", 3, null)
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("calendar_alarm_store", "AlarmRepositoryState", "notif_funnel", SyncFailurePolicy.PREFS_NAME).forEach {
            DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(false, "22:00", "07:00")
        // 09:00 → worker syncs TODAY + TOMORROW
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, zone))
        repo = AlarmRepository(context)
        notificationManager().cancelAll()
    }

    @After
    fun tearDown() {
        repo.store.removeAll()
        FixedClock.reset()
        notificationManager().cancelAll()
        listOf(SyncFailurePolicy.PREFS_NAME).forEach {
            DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
        }
    }

    private fun notificationManager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Number of visible notifications (SET/CHANGED/REMOVED/SYNC_FAILED). */
    private fun notificationCount(): Int =
        shadowOf(notificationManager()).allNotifications.size

    private suspend fun runWorker(fetcher: FakeFetcher): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<AlarmSyncWorker>(context).build()
        worker.fetcherOverride = fetcher
        return worker.doWork()
    }

    /** P2B #14: manual sync (input manual=true) — e.g. freeze active. */
    private suspend fun runWorkerManual(fetcher: FakeFetcher): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<AlarmSyncWorker>(context)
            .setInputData(androidx.work.workDataOf(AlarmSyncWorker.INPUT_MANUAL to true))
            .build()
        worker.fetcherOverride = fetcher
        return worker.doWork()
    }

    private fun existingAlarm(
        date: LocalDate, time: String, label: String = "Kantoor", type: String = "office",
        isSnooze: Boolean = false, userDisabled: Boolean = false, enabled: Boolean = true,
        triggerAtMillis: Long? = null
    ): AlarmStore.OwnedAlarm {
        val trigger = triggerAtMillis ?: ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant().toEpochMilli()
        return AlarmStore.OwnedAlarm(
            id = repo.store.nextId(),
            triggerAtMillis = trigger,
            timeLabel = time,
            label = label,
            date = date.toString(),
            type = type,
            enabled = enabled,
            isSnooze = isSnooze,
            userDisabled = userDisabled
        )
    }

    // ================= B2: validation & behavior via doWork =================

    @Test
    fun `invalid plan from server nothing overwritten and retry`() = runTest {
        // Existing alarm for tomorrow — must not be touched
        val existing = existingAlarm(tomorrow, "07:00")
        repo.store.save(existing)

        val fetcher = FakeFetcher().apply {
            // today: valid "off" (no alarm) so that branch is clean
            plans[today.toString()] = plan(today.toString(), null, "No alarm", "none")
            // tomorrow: INVALID wake time
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "25:99")
        }
        val result = runWorker(fetcher)

        assertTrue("invalid plan must return Result.retry()", result is ListenableWorker.Result.Retry)
        val after = repo.store.get(existing.id)
        assertNotNull("invalid plan must NOT delete existing alarm", after)
        assertEquals("07:00", after!!.timeLabel)
    }

    @Test
    fun `wake time with seconds (parseable but invalid format) nothing overwritten and retry`() = runTest {
        // LocalTime.parse("07:00:30") succeeds — only validatePlan rejects it.
        val existing = existingAlarm(tomorrow, "07:00")
        repo.store.save(existing)
        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), null, "No alarm", "none")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:00:30")
        }
        val result = runWorker(fetcher)
        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals("07:00", repo.store.get(existing.id)!!.timeLabel)
    }

    @Test
    fun `date-mismatch server plan nothing overwritten and retry`() = runTest {
        val existing = existingAlarm(tomorrow, "07:00")
        repo.store.save(existing)

        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), null, "No alarm", "none")
            // server responds with a DIFFERENT date than requested
            plans[tomorrow.toString()] = plan("2026-12-24", "07:00")
        }
        val result = runWorker(fetcher)

        assertTrue("date mismatch must retry", result is ListenableWorker.Result.Retry)
        assertNotNull(repo.store.get(existing.id))
    }

    @Test
    fun `wake time today already passed is skipped without error`() = runTest {
        // 09:00 now; server says for TODAY 07:00 (past)
        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), "07:00")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30")
        }
        val result = runWorker(fetcher)

        assertTrue("success (tomorrow succeeded)", result is ListenableWorker.Result.Success)
        // no alarm for today in the store
        assertTrue("no alarm in the past for today", repo.store.getAll().none { it.date == today.toString() })
        // but there is one for tomorrow
        assertTrue(repo.store.getAll().any { it.date == tomorrow.toString() && it.timeLabel == "07:30" })
    }

    @Test
    fun `identical plan for 2 days gives 0 event notifications`() = runTest {
        // Pre: tomorrow already has 07:30 Office
        repo.store.save(existingAlarm(tomorrow, "07:30", "Kantoor"))
        // Pre: today already has 08:00 Office (still in the future — 09:00 now? no: 08:00 is past)
        // → use 10:00 for today so it is pending
        repo.store.save(existingAlarm(today, "10:00", "Kantoor"))

        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), "10:00", "Kantoor")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        // identical plan → no SET/CHANGED/REMOVED notifications (only maybe SYNC_FAILED on failure — not here)
        assertEquals("identical plan → silent", 0, notificationCount())
    }

    @Test
    fun `off for tomorrow cancels existing alarm with 1 REMOVED notification`() = runTest {
        repo.store.save(existingAlarm(tomorrow, "07:00"))
        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), null, "No alarm", "none")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), null, "No alarm", "none")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("existing alarm for tomorrow is cancelled", repo.store.getAll().none { it.date == tomorrow.toString() })
        // REMOVED uses ID REMOVED_BASE + alarmId (2200+id) — check that there is ≥1 event notification
        assertTrue("1 REMOVED notification expected", notificationCount() >= 1)
    }

    @Test
    fun `counter reset after successful sync`() = runTest {
        val prefs = DeviceProtectedStorage.prefs(context, SyncFailurePolicy.PREFS_NAME)
        // Simulate 2 failed runs (counter=2)
        SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, io.github.calendaralarm.data.AppClock.now(), "error 1"))
        SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, io.github.calendaralarm.data.AppClock.now(), "error 2"))
        assertEquals(2, prefs.getInt(SyncFailurePolicy.KEY_CONSECUTIVE_FAILURES, 0))

        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), "10:00", "Kantoor")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("after success the fail counter must be reset", 0, prefs.getInt(SyncFailurePolicy.KEY_CONSECUTIVE_FAILURES, 0))
        // but the 12h dedup marker NOT cleared (Opus minor point)
    }

    // ================= B1: today may only be updated =================

    @Test
    fun `B1a today disabled (no record) sync creates nothing`() = runTest {
        // No record at all for today (pre-alarm "Turn off" deleted it)
        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), "10:00", "Kantoor")  // server says: today 10:00
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("today must NOT have a new alarm created", repo.store.getAll().none { it.date == today.toString() })
        assertTrue("but there is one for tomorrow", repo.store.getAll().any { it.date == tomorrow.toString() })
    }

    @Test
    fun `B1b alarm today already fired (record still there) plan now later no new alarm`() = runTest {
        // Alarm fired at 08:00; record still exists (ringing / not dismissed), now 09:00.
        // Server now says 10:00 → NO new alarm at 10:00 may come.
        val fired = existingAlarm(today, "08:00", "Kantoor")
        repo.store.save(fired)
        repo.store.save(existingAlarm(tomorrow, "07:30", "Kantoor")) // tomorrow identical → silent
        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), "10:00", "Kantoor")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("no alarm at 10:00 today after firing",
            repo.store.getAll().none { it.date == today.toString() && it.timeLabel == "10:00" })
        assertEquals("no SET notification", 0, notificationCount())
    }

    @Test
    fun `B1c snooze running and server says off for today snooze stays and no REMOVED`() = runTest {
        // Snooze record for today (still in the future)
        val snooze = existingAlarm(today, "09:30", "Kantoor", isSnooze = true,
            triggerAtMillis = ZonedDateTime.of(today, LocalTime.of(9, 30), zone).toInstant().toEpochMilli())
        repo.store.save(snooze)
        // Tomorrow already identical alarm → tomorrow gives no notification (else SET for tomorrow counts)
        repo.store.save(existingAlarm(tomorrow, "07:30", "Kantoor"))

        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), null, "No alarm", "none") // server: off
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        val after = repo.store.get(snooze.id)
        assertNotNull("running snooze record must NOT be cleaned up", after)
        assertTrue(after!!.isSnooze)
        assertEquals("no REMOVED notification on snooze+off-today (tomorrow is identical → silent)", 0, notificationCount())
    }

    @Test
    fun `B1d pending alarm today 10-00 plan 10-30 gets updated`() = runTest {
        // 09:00 now; pending alarm today 10:00
        val existing = existingAlarm(today, "10:00", "Kantoor")
        repo.store.save(existing)

        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), "10:30", "Kantoor") // Changed!
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        val todayAlarms = repo.store.getAll().filter { it.date == today.toString() && !it.isSnooze }
        assertEquals("today must have exactly 1 updated alarm", 1, todayAlarms.size)
        assertEquals("10:30", todayAlarms.first().timeLabel)
    }

    // ================= P2B #14: manual=true ignores freeze, keeps other guards =================

    @Test
    fun `B1 today off with pending alarm DOES cancel (pending exists)`() = runTest {
        // 09:00 now; pending alarm today 10:00; server says off for today
        // → B1 guard allows update (pending not empty) → cancelForDate may cancel
        val existing = existingAlarm(today, "10:00", "Kantoor")
        repo.store.save(existing)
        repo.store.save(existingAlarm(tomorrow, "07:30", "Kantoor"))

        val fetcher = FakeFetcher().apply {
            plans[today.toString()] = plan(today.toString(), null, "No alarm", "none")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        // B1 protects against CREATING; a still pending alarm may be removed on explicit "off".
        assertTrue("pending alarm today cancelled on off",
            repo.store.getAll().none { it.date == today.toString() })
        assertEquals("exactly 1 REMOVED notification (tomorrow identical → silent)", 1, notificationCount())
    }

    @Test
    fun `P2B14 manual=true ignores freeze and still syncs (tomorrow)`() = runTest {
        // Freeze ON (22:00–07:00 active at 23:00)
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 23, 0, 0, 0, zone))
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(true, "22:00", "07:00")
        try {
            val fetcher = FakeFetcher().apply {
                plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
            }
            // 23:00 → after 12:00, only tomorrow
            val result = runWorkerManual(fetcher)
            assertTrue("manual=true must sync despite freeze", result is ListenableWorker.Result.Success)
            assertTrue("tomorrow must have an alarm after manual sync",
                repo.store.getAll().any { it.date == tomorrow.toString() && it.timeLabel == "07:30" && it.enabled })
            assertTrue("fetcher must have been asked for tomorrow", fetcher.requestedDates.contains(tomorrow.toString()))
        } finally {
            io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(false, "22:00", "07:00")
        }
    }

    @Test
    fun `P2B14 without manual=true freeze IS respected (nothing synced)`() = runTest {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 23, 0, 0, 0, zone))
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(true, "22:00", "07:00")
        try {
            val fetcher = FakeFetcher().apply {
                plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
            }
            val result = runWorker(fetcher)  // no manual flag
            assertTrue("automated sync during freeze → success (skip)", result is ListenableWorker.Result.Success)
            assertTrue("freeze must NOT reach the fetcher", fetcher.requestedDates.isEmpty())
            assertTrue("no alarm may have been created", repo.store.getAll().none { it.date == tomorrow.toString() })
        } finally {
            io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(false, "22:00", "07:00")
        }
    }

    @Test
    fun `P2B14 manual=true keeps validation guard invalid plan not applied`() = runTest {
        // Freeze on + manual=true → freeze skipped, but invalid plan → NOTHING overwritten + retry
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 23, 0, 0, 0, zone))
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(true, "22:00", "07:00")
        try {
            val existing = existingAlarm(tomorrow, "07:00")
            repo.store.save(existing)
            val fetcher = FakeFetcher().apply {
                // "07:00:30" is parseable but invalid format — only validatePlan rejects it
                plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:00:30")
            }
            val result = runWorkerManual(fetcher)
            // P2B re-review B1: manual sync fails instead of retry (no endless "busy", no nightly retry)
            assertTrue("invalid plan → failure on manual=true", result is ListenableWorker.Result.Failure)
            assertEquals("existing alarm untouched", "07:00", repo.store.get(existing.id)!!.timeLabel)
        } finally {
            io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(false, "22:00", "07:00")
        }
    }

    @Test
    fun `P2B14 manual=true keeps today-guard B1 fired record is not re-armed`() = runTest {
        // 09:00 (before 12:00 → worker syncs today + tomorrow); freeze on; manual=true
        // Today: record of 08:00 fired (enabled=false, userDisabled=false)
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, zone))
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(true, "22:00", "07:00")
        // freeze is 22:00-07:00 → at 09:00 freeze is NOT active; enable it with a window covering 09:00
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(true, "08:00", "10:00")
        try {
            val fired = existingAlarm(today, "08:00", "Kantoor", enabled = false, userDisabled = false)
            repo.store.save(fired)
            repo.store.save(existingAlarm(tomorrow, "07:30", "Kantoor"))
            val fetcher = FakeFetcher().apply {
                plans[today.toString()] = plan(today.toString(), "10:00", "Kantoor")
                plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
            }
            val result = runWorkerManual(fetcher)
            assertTrue(result is ListenableWorker.Result.Success)
            val after = repo.store.get(fired.id)
            assertNotNull(after)
            assertFalse("B1 guard must also apply on manual=true: fired record today NOT re-enabled", after!!.enabled)
            assertTrue("no new enabled alarm for today",
                repo.store.getAll().none { it.date == today.toString() && it.enabled && !it.isSnooze })
        } finally {
            io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(false, "22:00", "07:00")
        }
    }

    @Test
    fun `P2B review#7 today fired auto-disabled record is NOT re-armed by sync`() = runTest {
        // P2B review#7: handleAlarmFired zet enabled=false (auto-disable, userDisabled=false)
        // for a record of TODAY that just fired. The worker B1 guard must prevent
        // the sync from re-arming/re-enabling this record for today.
        // 09:00 now; today's record of 08:00 fired → enabled=false, userDisabled=false
        val fired = existingAlarm(today, "08:00", "Kantoor", enabled = false, userDisabled = false)
        repo.store.save(fired)
        repo.store.save(existingAlarm(tomorrow, "07:30", "Kantoor"))

        val fetcher = FakeFetcher().apply {
            // server pushes a NEW plan for today (10:00) — identical to nothing existing
            plans[today.toString()] = plan(today.toString(), "10:00", "Kantoor")
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        val after = repo.store.get(fired.id)
        assertNotNull("fired record must still exist", after)
        assertFalse("P2B review#7: fired record of TODAY must NOT be re-enabled by sync", after!!.enabled)
        assertFalse(after.userDisabled)
        // and no new alarm for today may have been created
        assertTrue("no new enabled alarm for today",
            repo.store.getAll().none { it.date == today.toString() && it.enabled && !it.isSnooze })
    }

    @Test
    fun `P2B review#7 future date auto-disabled record IS consistently re-enabled`() = runTest {
        // P2B review#7: auto-disabled record (enabled=false, userDisabled=false) for TOMORROW
        // with an identical server plan → replaceForDate re-arms in AlarmManager and saves
        // enabled=true (consistent AlarmManager ↔ store).
        // 21:00 → worker syncs only tomorrow
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone))
        val autoDisabled = existingAlarm(tomorrow, "07:30", "Kantoor", enabled = false, userDisabled = false)
        repo.store.save(autoDisabled)

        val fetcher = FakeFetcher().apply {
            plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:30", "Kantoor")
        }
        val result = runWorker(fetcher)

        assertTrue(result is ListenableWorker.Result.Success)
        val after = repo.store.get(autoDisabled.id)
        assertNotNull(after)
        assertTrue("P2B review#7: auto-disabled record for a FUTURE date must be re-enabled after sync", after!!.enabled)
        assertFalse("userDisabled must stay false", after.userDisabled)
    }
}
