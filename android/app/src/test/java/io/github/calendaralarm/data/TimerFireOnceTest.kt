package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.receiver.TimerFireReceiver
import io.github.calendaralarm.service.TimerRingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.7.0: The timer must fire exactly once, no matter how many paths (AlarmManager receiver,
 * ViewModel loop, BootReceiver) detect the end at the same time. Double firing led in
 * ≤ v2.6.13 to two TimerRingingActivities of which the first stopped the sound via onDestroy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerFireOnceTest {

    private lateinit var context: Context
    private lateinit var store: TimerStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("calalarm_timer_state", Context.MODE_PRIVATE).edit().clear().commit()
        store = TimerStateStore(context)
    }

    private fun saveRunning() = store.save(
        TimerStateStore.TimerState(
            isRunning = true,
            endTimeWallClock = System.currentTimeMillis() - 10,
            totalMs = 9_000,
            remainingMsAtSave = 9_000,
            customHours = 0, customMinutes = 0, customSeconds = 9
        )
    )

    @Test
    fun claimFinished_onlyFirstCallerWins() {
        saveRunning()
        assertTrue(store.claimFinished())
        assertFalse(store.claimFinished())
        assertFalse(store.hasActiveTimer())
    }

    @Test
    fun claimFinished_pausedTimerIsNotClaimed() {
        store.save(
            TimerStateStore.TimerState(
                isRunning = false, endTimeWallClock = 0, totalMs = 9_000, remainingMsAtSave = 4_000,
                customHours = 0, customMinutes = 0, customSeconds = 9
            )
        )
        assertFalse(store.claimFinished())
        assertEquals(4_000L, store.getRemainingMs())
    }

    @Test
    fun claimFinished_concurrentCallers_exactlyOneWins() {
        saveRunning()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = AtomicInteger(0)
        repeat(8) {
            pool.submit {
                start.await()
                if (TimerStateStore(context).claimFinished()) wins.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
    }

    @Test
    fun fire_startsRingServiceOnce() {
        saveRunning()
        assertTrue(TimerFireReceiver.fire(context, "test-alarmmanager"))
        assertFalse(TimerFireReceiver.fire(context, "test-viewmodel"))

        val app = shadowOf(context as android.app.Application)
        val started = generateSequence { app.nextStartedService }
            .filter { it.component?.className == TimerRingService::class.java.name }
            .count()
        assertEquals(1, started)
    }
}
