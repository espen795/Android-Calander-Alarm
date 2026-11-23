package io.github.calendaralarm.worker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import io.github.calendaralarm.data.DeviceProtectedStorage
import io.github.calendaralarm.data.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * P2A #6 end-to-end: AlarmSyncWorker with a failing network layer.
 *
 * In Robolectric there is no server, so every fetch fails — exactly the
 * failure condition we test: after 3 consecutive runs the
 * SYNC_FAILED notification must be decidable (notifiedAt set), before that not.
 * Every TestListenableWorkerBuilder creates a NEW worker — just like WorkManager —
 * so this also tests that the counter is persistent across instances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSyncWorkerTest {

    private lateinit var context: Context
    private val zone = java.time.ZoneId.of("Europe/Amsterdam")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear ALL relevant state (CE + DP) so every test starts clean
        DeviceProtectedStorage.prefs(context, SyncFailurePolicy.PREFS_NAME).edit().clear().commit()
        context.getSharedPreferences(SyncFailurePolicy.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "notif_funnel").edit().clear().commit()
        context.getSharedPreferences("notif_funnel", Context.MODE_PRIVATE).edit().clear().commit()
        // Freeze OFF (otherwise the worker skips)
        val settings = io.github.calendaralarm.CalendarAlarmApp.settingsRepository
        settings.updateWekkerFreeze(false, "22:00", "07:00")
        // 21:00 — after 12:00, so only tomorrow gets synced
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone))
        notificationManager().cancelAll()
    }

    @After
    fun tearDown() {
        FixedClock.reset()
        DeviceProtectedStorage.prefs(context, SyncFailurePolicy.PREFS_NAME).edit().clear().commit()
        notificationManager().cancelAll()
    }

    private fun notificationManager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** notifiedAt > 0 means: the worker decided to notify SYNC_FAILED. */
    private fun failNotifiedAt(): Long =
        DeviceProtectedStorage.prefs(context, SyncFailurePolicy.PREFS_NAME)
            .getLong(SyncFailurePolicy.KEY_FAIL_NOTIFIED_AT, 0)

    private fun failureCount(): Int =
        DeviceProtectedStorage.prefs(context, SyncFailurePolicy.PREFS_NAME)
            .getInt(SyncFailurePolicy.KEY_CONSECUTIVE_FAILURES, 0)

    private suspend fun runWorkerOnce(): androidx.work.ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<AlarmSyncWorker>(context).build()
        return worker.doWork()
    }

    @Test
    fun `after 3 consecutive failed runs there is a SYNC_FAILED notification`() = runTest {
        // Runs 1 and 2: silent (counter < 3). Every run = new worker instance, just like WorkManager.
        runWorkerOnce()
        runWorkerOnce()
        assertEquals("first 2 failed runs must not post a SYNC_FAILED notification", 0, failNotifiedAt())
        assertEquals(2, failureCount())

        // Run 3: notify (notifiedAt set to now)
        runWorkerOnce()
        val firstNotifyAt = failNotifiedAt()
        assertTrue("3rd consecutive failed run must notify SYNC_FAILED (notifiedAt set)", firstNotifyAt > 0)
        assertEquals(3, failureCount())

        // Run 4 within 12h: silent (dedup — notifiedAt unchanged)
        runWorkerOnce()
        assertEquals("4th run within 12h must be silent (dedup)", firstNotifyAt, failNotifiedAt())
        assertEquals(4, failureCount())

        // Run 5 after 12h+1ms: notify again (notifiedAt moves forward)
        FixedClock.advance(12L * 3_600_000L + 1)
        runWorkerOnce()
        assertTrue("after 12h it must notify again", failNotifiedAt() > firstNotifyAt)
    }

    @Test
    fun `counter is persistent across worker instances`() = runTest {
        // Simulate 2 runs in "instance A" and the 3rd in "instance B" — the prefs counter must keep counting
        runWorkerOnce()
        runWorkerOnce()
        assertEquals("counter must be in DP prefs after 2 runs", 2, failureCount())
        runWorkerOnce()
        assertEquals("3rd run in a new instance must have notified", 3, failureCount())
        assertTrue("notifiedAt must be set after the 3rd run", failNotifiedAt() > 0)
    }
}
