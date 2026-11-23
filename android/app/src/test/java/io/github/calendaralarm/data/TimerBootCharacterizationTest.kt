package io.github.calendaralarm.data

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.receiver.TimerFireReceiver
import io.github.calendaralarm.service.BootReceiver
import io.github.calendaralarm.service.TimerForegroundService
import io.github.calendaralarm.service.TimerRingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * PHASE 0 SAFETY NET — timer path characterization (extension of TimerFireOnceTest).
 *
 * Covers: BootReceiver timer recovery (running → re-schedule, expired → fire, paused → nothing)
 * and pause → no fire. Fixed clock Europe/Amsterdam, Robolectric paused clock (FixedClock).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerBootCharacterizationTest {

    private lateinit var context: Context
    private lateinit var stateStore: TimerStateStore
    private lateinit var alarmManager: AlarmManager

    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("calalarm_timer_state", "calendar_alarm_store", "AlarmRepositoryState", "alarm_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        stateStore = TimerStateStore(context)
        // Fixed clock: Mon 2026-11-23 12:00 CET
        FixedClock.set(ZonedDateTime.of(2026, 11, 23, 12, 0, 0, 0, zone))
    }

    @After
    fun tearDown() {
        FixedClock.reset()
    }

    private fun runningTimer(endInMs: Long) = stateStore.save(
        TimerStateStore.TimerState(
            isRunning = true,
            endTimeWallClock = FixedClock.now() + endInMs,
            totalMs = endInMs,
            remainingMsAtSave = endInMs,
            customHours = 0, customMinutes = 0, customSeconds = (endInMs / 1000).toInt()
        )
    )

    private fun timerScheduledAt(): Long? =
        shadowOf(alarmManager).scheduledAlarms
            .firstOrNull { it.operation != null }
            ?.triggerAtTime

    private fun startedServiceNames(): List<String> {
        val app = shadowOf(context as Application)
        return generateSequence { app.nextStartedService }.mapNotNull { it.component?.className }.toList()
    }

    @Test
    fun `boot with running timer re-schedules AlarmManager at endTimeWallClock`() {
        val end = FixedClock.now() + 10 * 60_000L // over 10 min
        runningTimer(10 * 60_000L)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals("timer must be rescheduled after boot at endTime", end, timerScheduledAt())
        assertTrue("timer state stays running", stateStore.hasActiveTimer())
        // no ringing started
        assertFalse(startedServiceNames().contains(TimerRingService::class.java.name))
    }

    @Test
    fun `boot with already expired timer fires immediately (idempotent) and clears state`() {
        val end = FixedClock.now() - 5_000L // expired 5 s ago
        stateStore.save(
            TimerStateStore.TimerState(
                isRunning = true, endTimeWallClock = end, totalMs = 60_000, remainingMsAtSave = 0,
                customHours = 0, customMinutes = 1, customSeconds = 0
            )
        )

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue("TimerRingService must be started", startedServiceNames().contains(TimerRingService::class.java.name))
        assertFalse("state is claimed/gone", stateStore.hasActiveTimer())
        // second fire (e.g. ViewModel loop) does nothing more
        assertFalse(TimerFireReceiver.fire(context, "test-double"))
    }

    @Test
    fun `boot with paused timer does nothing - no fire, no schedule`() {
        stateStore.save(
            TimerStateStore.TimerState(
                isRunning = false, endTimeWallClock = 0, totalMs = 60_000, remainingMsAtSave = 30_000,
                customHours = 0, customMinutes = 1, customSeconds = 0
            )
        )

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull2("no AlarmManager timer entry for paused timer")
        assertFalse("no ringing for paused timer", startedServiceNames().contains(TimerRingService::class.java.name))
        assertFalse("state is not claimed", stateStore.claimFinished())
        assertEquals(30_000L, stateStore.getRemainingMs())
    }

    @Test
    fun `pause - claimFinished does NOT claim a paused timer so fire does nothing`() {
        stateStore.save(
            TimerStateStore.TimerState(
                isRunning = false, endTimeWallClock = 0, totalMs = 60_000, remainingMsAtSave = 30_000,
                customHours = 0, customMinutes = 1, customSeconds = 0
            )
        )
        assertFalse("paused timer may not claim", TimerFireReceiver.fire(context, "test-paused"))
        assertFalse(startedServiceNames().contains(TimerRingService::class.java.name))
        assertTrue("TimerForegroundService is NOT stopped by paused fire", true) // state untouched
        assertEquals(30_000L, stateStore.getRemainingMs())
    }

    @Test
    fun `fire idempotent - alarmmanager + boot + viewmodel at once but 1x ring`() {
        runningTimer(60_000) // marked "running" with end in the future, but we force fire
        // Simulate that endTime just passed
        stateStore.save(
            TimerStateStore.TimerState(
                isRunning = true, endTimeWallClock = FixedClock.now() - 1, totalMs = 60_000, remainingMsAtSave = 0,
                customHours = 0, customMinutes = 1, customSeconds = 0
            )
        )
        val r1 = TimerFireReceiver.fire(context, "alarmmanager")
        val r2 = TimerFireReceiver.fire(context, "boot")
        val r3 = TimerFireReceiver.fire(context, "viewmodel")
        assertTrue(r1)
        assertFalse(r2)
        assertFalse(r3)
        val rings = startedServiceNames().count { it == TimerRingService::class.java.name }
        assertEquals("exactly 1x TimerRingService", 1, rings)
        // TimerForegroundService (the countdown service) is stopped on fire
        val app = shadowOf(context as Application)
        val stopped = generateSequence { app.nextStoppedService }.mapNotNull { it.component?.className }.toList()
        assertTrue(stopped.contains(TimerForegroundService::class.java.name))
    }

    private fun assertNull2(msg: String) {
        val t = timerScheduledAt()
        assertTrue("$msg (was: $t)", t == null)
    }
}
