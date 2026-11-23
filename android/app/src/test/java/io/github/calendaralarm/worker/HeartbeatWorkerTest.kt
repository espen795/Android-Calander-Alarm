package io.github.calendaralarm.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import io.github.calendaralarm.data.DeviceProtectedStorage
import io.github.calendaralarm.data.FixedClock
import io.github.calendaralarm.sync.HeartbeatReporter
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * Phase 4 (principle 7): HeartbeatWorker — NEVER fails on network error
 * (always Result.success, so there is no retry storm; the next
 * periodic run (3h) tries again).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HeartbeatWorkerTest {

    private lateinit var context: Context

    /** Fake reporter that simulates a network error. */
    private class FailingReporter(context: Context) : HeartbeatReporter(context) {
        var calls = 0
        override suspend fun send(): HeartbeatResult {
            calls++
            return HeartbeatResult(false, "Network error: ConnectException", 0)
        }
    }

    /** Fake reporter that simulates success. */
    private class OkReporter(context: Context) : HeartbeatReporter(context) {
        var calls = 0
        override suspend fun send(): HeartbeatResult {
            calls++
            return HeartbeatResult(true, scheduledCount = 3)
        }
    }

    /** Fake reporter that throws an exception (worker must catch it). */
    private class ThrowingReporter(context: Context) : HeartbeatReporter(context) {
        override suspend fun send(): HeartbeatResult {
            throw RuntimeException("unexpected crash")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("calendar_alarm_store").forEach {
            DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 30, 0, 0, java.time.ZoneId.of("Europe/Amsterdam")))
    }

    @After
    fun tearDown() {
        FixedClock.reset()
    }

    private suspend fun runWorker(reporter: HeartbeatReporter): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<HeartbeatWorker>(context).build()
        worker.reporterOverride = reporter
        return worker.doWork()
    }

    @Test
    fun `network error in reporter still gives Result success`() = runTest {
        val failing = FailingReporter(context)
        val result = runWorker(failing)
        assertTrue("heartbeat error must not trigger retry/failure", result is ListenableWorker.Result.Success)
        assertTrue("reporter must have been called 1x", failing.calls == 1)
    }

    @Test
    fun `success gives Result success`() = runTest {
        val ok = OkReporter(context)
        val result = runWorker(ok)
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(ok.calls == 1)
    }

    @Test
    fun `exception in reporter is caught and gives Result success`() = runTest {
        val result = runWorker(ThrowingReporter(context))
        assertTrue("even a crash in the reporter must not make the worker fail",
            result is ListenableWorker.Result.Success)
    }
}
