package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P2B review#9: transactional boundary (AlarmTransactions) — concurrency tests.
 *
 * Scenario (lost-update): thread A calls setEnabled(false, userAction=true) (user turns
 * the alarm OFF), thread B calls replaceForDate with an identical plan (sync). Without
 * a mutex, B can sit between A's read and write → the userDisabled trail is
 * lost and the sync re-arms the alarm anyway. With the mutex the user's choice wins:
 * userDisabled=true stays set and there is no enabled re-arm.
 *
 * Runs 50× in a loop for determinism (race is timing-dependent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmTransactionsTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository
    private lateinit var store: AlarmStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        repo = AlarmRepository(context)
        store = repo.store
    }

    @After
    fun tearDown() {
        store.removeAll()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
    }

    private fun makeAlarm(date: LocalDate, time: String = "07:00"): AlarmStore.OwnedAlarm {
        val zone = ZoneId.systemDefault()
        val trigger = ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant().toEpochMilli()
        return AlarmStore.OwnedAlarm(
            id = store.nextId(),
            triggerAtMillis = trigger,
            timeLabel = time,
            label = "Kantoor",
            date = date.toString(),
            type = "office",
            enabled = true
        )
    }

    @Test
    fun `concurrent setEnabled user-off vs replaceForDate same plan user wins 50x`() {
        repeat(50) { iteration ->
            store.removeAll()
            val date = LocalDate.now().plusDays(1)
            val alarm = makeAlarm(date)
            store.save(alarm)
            val id = alarm.id

            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            // Thread A: user turns the alarm OFF (userAction=true → userDisabled)
            val a = pool.submit {
                start.await()
                repo.setEnabled(id, false, userAction = true)
                done.countDown()
            }
            // Thread B: sync with an identical plan for the same date
            val b = pool.submit {
                start.await()
                repo.replaceForDate(date, "07:00", "Kantoor", "office", silent = true)
                done.countDown()
            }
            start.countDown()
            assertTrue("threads must finish within 10s (iteration $iteration)",
                done.await(10, TimeUnit.SECONDS))
            a.get(); b.get()
            pool.shutdown()

            val after = store.get(id)
            assertNotNull("iteration $iteration: record must exist", after)
            assertTrue(
                "iteration $iteration: userDisabled must be set (user action wins over sync), but was: enabled=${after!!.enabled} userDisabled=${after.userDisabled}",
                after.userDisabled
            )
            assertFalse(
                "iteration $iteration: sync must NOT re-enable the user-disabled alarm (enabled must stay false), was enabled=${after.enabled}",
                after.enabled
            )
            // there may be no extra enabled smart alarm for that date (no re-arm)
            val enabledForDate = store.getAll().count { it.date == date.toString() && it.enabled && !it.isSnooze }
            assertEquals("iteration $iteration: no enabled alarm for the date after user-off + sync", 0, enabledForDate)
        }
    }

    @Test
    fun `AlarmTransactions is reentrant (nested run does not deadlock)`() {
        val outer = AlarmTransactions.run {
            val inner = AlarmTransactions.run { 42 }
            inner + 1
        }
        assertEquals(43, outer)
    }
}
