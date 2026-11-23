package io.github.calendaralarm.worker

import io.github.calendaralarm.data.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P2A #13: strict validation of the server plan (date + wake time).
 * Invalid → null → caller may NOT overwrite anything and must retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanValidationTest {

    private val zone = ZoneId.of("Europe/Amsterdam")

    @Before
    fun setUp() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone))
    }

    @After
    fun tearDown() = FixedClock.reset()

    @Test
    fun `valid date and wake time validate`() {
        assertEquals(LocalDate.of(2026, 11, 23), AlarmSyncWorker.validatePlan("2026-11-23", "07:30"))
        assertEquals(LocalDate.of(2026, 11, 23), AlarmSyncWorker.validatePlan("2026-11-23", null))
        assertEquals(LocalDate.of(2026, 12, 31), AlarmSyncWorker.validatePlan("2026-12-31", "23:59"))
        assertEquals(LocalDate.of(2026, 1, 1), AlarmSyncWorker.validatePlan("2026-01-01", "00:00"))
    }

    @Test
    fun `invalid date is rejected`() {
        assertNull(AlarmSyncWorker.validatePlan("2026-13-01", "07:00"))   // month 13
        assertNull(AlarmSyncWorker.validatePlan("2026-02-30", "07:00"))   // Feb 30
        assertNull(AlarmSyncWorker.validatePlan("2026-11-31", "07:00"))   // Nov 31
        assertNull(AlarmSyncWorker.validatePlan("2026-00-10", "07:00"))   // month 0
        assertNull(AlarmSyncWorker.validatePlan("2026-11-00", "07:00"))   // day 0
        assertNull(AlarmSyncWorker.validatePlan("22-11-2026", "07:00"))   // wrong order
        assertNull(AlarmSyncWorker.validatePlan("2026/11/23", "07:00"))   // wrong separator
        assertNull(AlarmSyncWorker.validatePlan("", "07:00"))
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23 ", "07:00"))  // trailing space
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23T00:00", "07:00"))
    }

    @Test
    fun `invalid wake time is rejected`() {
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", "24:00"))
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", "7:30"))    // no leading zero
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", "07:60"))
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", "07:3"))
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", ""))
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", "07:00:00"))
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", "abc"))
        assertNull(AlarmSyncWorker.validatePlan("2026-11-23", " 07:00"))
    }

    @Test
    fun `leap year Feb 29 is valid`() {
        assertEquals(LocalDate.of(2028, 2, 29), AlarmSyncWorker.validatePlan("2028-02-29", "06:00"))
        // 2026 is NOT a leap year → Feb 29 does not exist
        assertNull(AlarmSyncWorker.validatePlan("2026-02-29", "06:00"))
    }
}
