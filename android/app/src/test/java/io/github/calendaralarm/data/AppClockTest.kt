package io.github.calendaralarm.data

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Guards the safety net itself: if FixedClock no longer controls the app clock, all
 * time-dependent tests run on the real clock and pass by accident. That happened until 2026-11-22.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppClockTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    @After fun tearDown() = FixedClock.reset()

    @Test fun `FixedClock controls AppClock`() {
        val t = ZonedDateTime.of(2030, 1, 7, 8, 0, 0, 0, zone)
        FixedClock.set(t)
        assertEquals(t.toInstant().toEpochMilli(), AppClock.now())
        FixedClock.advance(60_000)
        assertEquals(t.toInstant().toEpochMilli() + 60_000, AppClock.now())
    }

    @Test fun `computeNextTrigger follows the fixed clock, not the system clock`() {
        // Mon 2030-01-07 08:00 → Mon-Fri 07:00 is already past today → Tue 2030-01-08 07:00 CET
        FixedClock.set(ZonedDateTime.of(2030, 1, 7, 8, 0, 0, 0, zone))
        val repo = AlarmRepository(ApplicationProvider.getApplicationContext())
        assertEquals(
            Instant.parse("2030-01-08T06:00:00Z"),
            repo.computeNextTrigger("07:00", listOf(1, 2, 3, 4, 5), null)
        )
    }
}
