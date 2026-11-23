package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P2A small (Opus review): isWekkerFrozen() is AppClock-based (was Calendar.getInstance)
 * → freeze is testable via FixedClock, for both sides of the night window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WekkerFreezeClockTest {

    private lateinit var repo: SettingsRepository
    private val zone = ZoneId.of("Europe/Amsterdam")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("calendar_alarm_settings", Context.MODE_PRIVATE).edit().clear().commit()
        repo = SettingsRepository(context)
        repo.updateWekkerFreeze(true, "22:00", "07:00")
    }

    @After
    fun tearDown() {
        FixedClock.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("calendar_alarm_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `freeze applies at 23h00 (inside night window 22-07)`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 23, 0, 0, 0, zone))
        assertTrue(repo.isWekkerFrozen())
    }

    @Test
    fun `freeze applies at 03h00 (night window runs across midnight)`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 23, 3, 0, 0, 0, zone))
        assertTrue(repo.isWekkerFrozen())
    }

    @Test
    fun `freeze does NOT apply at 12h00 (outside window)`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 12, 0, 0, 0, zone))
        assertFalse(repo.isWekkerFrozen())
    }

    @Test
    fun `freeze does NOT apply at 21h59 (just before the window)`() {
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 59, 0, 0, zone))
        assertFalse(repo.isWekkerFrozen())
    }

    @Test
    fun `freeze disabled is never frozen, even inside the window`() {
        repo.updateWekkerFreeze(false, "22:00", "07:00")
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 23, 0, 0, 0, zone))
        assertFalse(repo.isWekkerFrozen())
    }
}
