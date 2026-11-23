package io.github.calendaralarm.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.DeviceProtectedStorage
import io.github.calendaralarm.data.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P2A #13 (503 behavior): on a server error (in Robolectric: no server →
 * fetchAlarmPlan fails, just like a 503) the EXISTING alarm in the store must
 * be PRESERVED — the worker writes nothing on failure.
 *
 * Server side this is already covered by test_plan_cache.py / test_alarm_next_api.py
 * (calendar down without cache → 503; app must keep existing alarm + retry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Sync503BehaviorTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val tomorrow = LocalDate.of(2026, 11, 23)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, SyncFailurePolicy.PREFS_NAME).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "notif_funnel").edit().clear().commit()
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(false, "22:00", "07:00")
        // 21:00 — only tomorrow is synced
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone))
        repo = AlarmRepository(context)
    }

    @After
    fun tearDown() {
        repo.store.removeAll()
        FixedClock.reset()
        DeviceProtectedStorage.prefs(context, SyncFailurePolicy.PREFS_NAME).edit().clear().commit()
    }

    @Test
    fun `on server error (503 equivalent) existing alarm is preserved`() {
        // Existing alarm for tomorrow in the store (simulate: previous sync was successful)
        val existing = AlarmStore.OwnedAlarm(
            id = repo.store.nextId(),
            triggerAtMillis = ZonedDateTime.of(tomorrow, java.time.LocalTime.parse("07:00"), zone).toInstant().toEpochMilli(),
            timeLabel = "07:00",
            label = "Kantoor",
            date = tomorrow.toString(),
            type = "office",
            enabled = true
        )
        repo.store.save(existing)
        assertNotNull(repo.store.get(existing.id))

        // Worker run without reachable server → fails (just like 503)
        val worker = androidx.work.testing.TestListenableWorkerBuilder<AlarmSyncWorker>(context).build()
        kotlinx.coroutines.runBlocking { worker.doWork() }

        // Existing alarm must be UNTOUCHED
        val after = repo.store.get(existing.id)
        assertNotNull("503/failure must NOT delete existing alarm", after)
        assertEquals("07:00", after!!.timeLabel)
        assertEquals(tomorrow.toString(), after.date)
        assertEquals(1, repo.store.getAll().size)
    }
}
