package io.github.calendaralarm.worker

import io.github.calendaralarm.data.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * P2A #12: dates that the sync worker fetches per run.
 *
 * - before 12:00 local → [today, tomorrow] (today's wake time can still lie in the future)
 * - at/after 12:00 → only [tomorrow]
 * - today's wake time in the past → isWakeTimeStillPossible = false
 *   (the worker then does NOT schedule an alarm in the past)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSyncDatesTest {

    private val zone = ZoneId.of("Europe/Amsterdam")

    @Before
    fun setUp() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone)) // Sun 21:00
    }

    @After
    fun tearDown() = FixedClock.reset()

    @Test
    fun `before 12-00 today and tomorrow are synced`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 9, 30, 0, 0, zone)) // Sun 09:30
        val dates = AlarmSyncWorker.datesToSync()
        assertEquals(listOf(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 23)), dates)
    }

    @Test
    fun `at 00-00 today and tomorrow are synced`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, zone))
        assertEquals(2, AlarmSyncWorker.datesToSync().size)
    }

    @Test
    fun `at exactly 12-00 only tomorrow`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 12, 0, 0, 0, zone))
        val dates = AlarmSyncWorker.datesToSync()
        assertEquals(listOf(LocalDate.of(2026, 11, 23)), dates)
    }

    @Test
    fun `after 12-00 only tomorrow`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone))
        val dates = AlarmSyncWorker.datesToSync()
        assertEquals(listOf(LocalDate.of(2026, 11, 23)), dates)
    }

    @Test
    fun `wake time today in the future is still possible`() {
        // 09:30 now, wake time 10:00 today → possible
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 9, 30, 0, 0, zone))
        assertTrue(AlarmSyncWorker.isWakeTimeStillPossible(LocalDate.of(2026, 11, 22), "10:00"))
    }

    @Test
    fun `wake time today in the past is no longer possible`() {
        // 21:00 now, wake time 07:00 today → past → no alarm in the past
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone))
        assertFalse(AlarmSyncWorker.isWakeTimeStillPossible(LocalDate.of(2026, 11, 22), "07:00"))
    }

    @Test
    fun `wake time tomorrow is always still possible`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 23, 59, 0, 0, zone))
        assertTrue(AlarmSyncWorker.isWakeTimeStillPossible(LocalDate.of(2026, 11, 23), "00:00"))
    }

    @Test
    fun `DST switch in spring is handled correctly by ZonedDateTime`() {
        // 2026-03-29 02:00 → 03:00 (spring DST switch). Wake time 07:00 on that day is still
        // in the future when it is now 06:00 (even though 02:30 does not "exist").
        FixedClock.set(ZonedDateTime.of(2026, 3, 29, 6, 0, 0, 0, zone))
        assertTrue(AlarmSyncWorker.isWakeTimeStillPossible(LocalDate.of(2026, 3, 29), "07:00"))
    }
}
