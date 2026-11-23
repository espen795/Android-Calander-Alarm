package io.github.calendaralarm.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.DeviceProtectedStorage
import io.github.calendaralarm.data.FixedClock
import io.github.calendaralarm.sync.HeartbeatReporter
import io.github.calendaralarm.sync.SyncRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase 4 (principle 7): after every SUCCESSFUL AlarmSyncWorker run a heartbeat
 * is sent (a) — and a heartbeat failure must NOT change the sync Result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmSyncHeartbeatTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 11, 22)
    private val tomorrow = LocalDate.of(2026, 11, 23)

    private class FakeFetcher : AlarmPlanFetcher {
        val plans = mutableMapOf<String, SyncRepository.SyncResult>()
        override suspend fun fetchAlarmPlan(date: String): SyncRepository.SyncResult =
            plans[date] ?: SyncRepository.SyncResult(false, error = "no plan")
        override fun close() {}
    }

    private class FakeHeartbeatReporter(context: Context) : HeartbeatReporter(context) {
        var calls = 0
        var result = HeartbeatResult(true, scheduledCount = 1)
        override suspend fun send(): HeartbeatResult {
            calls++
            return result
        }
    }

    private fun plan(date: String, wakeTime: String?) =
        SyncRepository.SyncResult(
            true,
            SyncRepository.AlarmPlan(date, wakeTime, "Office", "office", "high", 1, null)
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("calendar_alarm_store", "AlarmRepositoryState", SyncFailurePolicy.PREFS_NAME).forEach {
            DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        io.github.calendaralarm.CalendarAlarmApp.settingsRepository.updateWekkerFreeze(false, "22:00", "07:00")  // PRESERVED: method name
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone)) // evening → only tomorrow
        repo = AlarmRepository(context)
    }

    @After
    fun tearDown() {
        repo.store.removeAll()
        FixedClock.reset()
        listOf(SyncFailurePolicy.PREFS_NAME).forEach {
            DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
        }
    }

    private suspend fun runWorker(fetcher: FakeFetcher, hb: FakeHeartbeatReporter): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<AlarmSyncWorker>(context).build()
        worker.fetcherOverride = fetcher
        worker.heartbeatReporterOverride = hb
        return worker.doWork()
    }

    @Test
    fun `successful sync triggers heartbeat`() = runTest {
        val fetcher = FakeFetcher().apply { plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:00") }
        val hb = FakeHeartbeatReporter(context)
        val result = runWorker(fetcher, hb)
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("after a successful sync 1 heartbeat must be sent", 1, hb.calls)
    }

    @Test
    fun `failed sync still sends a heartbeat and stays retry`() = runTest {
        // P4 review B2: also after a (partially) failed sync, report what the phone has,
        // otherwise the plan cache can be newer than the heartbeat (false missing).
        val fetcher = FakeFetcher() // no plan → failure
        val hb = FakeHeartbeatReporter(context)
        val result = runWorker(fetcher, hb)
        assertTrue("sync Result stays retry", result is ListenableWorker.Result.Retry)
        assertEquals("even on failed sync 1 heartbeat", 1, hb.calls)
    }

    @Test
    fun `heartbeat error does not change sync Result`() = runTest {
        val fetcher = FakeFetcher().apply { plans[tomorrow.toString()] = plan(tomorrow.toString(), "07:00") }
        val hb = FakeHeartbeatReporter(context).apply {
            result = HeartbeatReporter.HeartbeatResult(false, "Network error: ConnectException", 0)
        }
        val result = runWorker(fetcher, hb)
        assertTrue("sync succeeded; a heartbeat error must not break that",
            result is ListenableWorker.Result.Success)
        assertEquals(1, hb.calls)
    }
}
