package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * F7: Unit tests for AlarmRepository.
 *
 * Tests: computeNextTrigger() for one-shot and repeat, pre-alarm time calculation,
 * past time rejection, computeNextTriggerAfterToday.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmRepositoryTest {

    private lateinit var repo: AlarmRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        repo = AlarmRepository(context)
    }

    @After
    fun tearDown() {
        repo.store.removeAll()
    }

    // ============== computeNextTrigger: one-shot ==============

    @Test
    fun `computeNextTrigger one-shot future time today returns today`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Set time to 1 hour in the future
        val futureTime = now.plusHours(1).toLocalTime()
        val timeLabel = String.format("%02d:%02d", futureTime.hour, futureTime.minute)

        val trigger = repo.computeNextTrigger(timeLabel, emptyList(), null)
        assertNotNull(trigger)

        val triggerDateTime = LocalDateTime.ofInstant(trigger, zone)
        assertEquals(now.toLocalDate(), triggerDateTime.toLocalDate())
    }

    @Test
    fun `computeNextTrigger one-shot past time today returns tomorrow`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Set time to 1 hour in the past
        val pastTime = now.minusHours(1).toLocalTime()
        val timeLabel = String.format("%02d:%02d", pastTime.hour, pastTime.minute)

        val trigger = repo.computeNextTrigger(timeLabel, emptyList(), null)
        assertNotNull(trigger)

        val triggerDateTime = LocalDateTime.ofInstant(trigger, zone)
        assertEquals(now.toLocalDate().plusDays(1), triggerDateTime.toLocalDate())
    }

    @Test
    fun `computeNextTrigger with targetDate returns that date`() {
        val targetDate = LocalDate.now().plusDays(5)
        val trigger = repo.computeNextTrigger("08:30", emptyList(), targetDate)
        assertNotNull(trigger)

        val zone = ZoneId.systemDefault()
        val triggerDate = LocalDate.ofInstant(trigger, zone)
        assertEquals(targetDate, triggerDate)

        val triggerTime = LocalTime.ofInstant(trigger, zone)
        assertEquals(LocalTime.of(8, 30), triggerTime)
    }

    @Test
    fun `computeNextTrigger invalid time format returns null`() {
        assertNull(repo.computeNextTrigger("25:00", emptyList(), null))
        assertNull(repo.computeNextTrigger("abc", emptyList(), null))
        assertNull(repo.computeNextTrigger("7:00", emptyList(), null)) // not zero-padded
    }

    // ============== computeNextTrigger: repeat ==============

    @Test
    fun `computeNextTrigger repeat finds next matching day`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Find tomorrow's day of week
        val tomorrowDow = now.toLocalDate().plusDays(1).dayOfWeek.value

        // Use a time 2 hours in the future to ensure it's valid
        val futureTime = now.plusHours(2).toLocalTime()
        val timeLabel = String.format("%02d:%02d", futureTime.hour, futureTime.minute)

        val trigger = repo.computeNextTrigger(timeLabel, listOf(tomorrowDow), null)
        assertNotNull(trigger)

        val triggerDate = LocalDate.ofInstant(trigger, zone)
        assertEquals(tomorrowDow, triggerDate.dayOfWeek.value)
    }

    @Test
    fun `computeNextTrigger repeat with multiple days finds earliest`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Get day of week for today, tomorrow, and day after
        val todayDow = now.toLocalDate().dayOfWeek.value
        val tomorrowDow = now.toLocalDate().plusDays(1).dayOfWeek.value
        val dayAfterDow = now.toLocalDate().plusDays(2).dayOfWeek.value

        // Use a time far in the future (23:59) to ensure it's always valid
        val timeLabel = "23:59"

        // Include tomorrow and day after — should pick tomorrow (earliest)
        val trigger = repo.computeNextTrigger(timeLabel, listOf(tomorrowDow, dayAfterDow), null)
        assertNotNull(trigger)

        val triggerDate = LocalDate.ofInstant(trigger, zone)
        // Should be tomorrow or today (if today matches and time is in future)
        assertTrue(triggerDate.dayOfWeek.value == tomorrowDow || triggerDate.dayOfWeek.value == todayDow)
    }

    @Test
    fun `computeNextTrigger repeat wraps around week`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Pick a day that is NOT today or tomorrow (to test wrap-around)
        val targetDow = now.toLocalDate().plusDays(3).dayOfWeek.value

        val timeLabel = "23:59" // late time to ensure future
        val trigger = repo.computeNextTrigger(timeLabel, listOf(targetDow), null)
        assertNotNull(trigger)

        val triggerDate = LocalDate.ofInstant(trigger, zone)
        assertEquals(targetDow, triggerDate.dayOfWeek.value)
        // Should be within the next 7 days
        assertTrue(triggerDate.toEpochDay() - now.toLocalDate().toEpochDay() in 1..7)
    }

    // ============== F4: Pre-alarm time calculation ==============

    @Test
    fun `computePreAlarmTime returns trigger minus 60 minutes`() {
        val triggerAtMillis = System.currentTimeMillis() + 2 * 60 * 60 * 1000 // 2 hours from now
        val preAlarmTime = repo.computePreAlarmTime(triggerAtMillis)

        assertNotNull(preAlarmTime)
        assertEquals(triggerAtMillis - 60 * 60 * 1000, preAlarmTime)
    }

    @Test
    fun `computePreAlarmTime returns null if pre-alarm time is in the past`() {
        // Alarm is 30 minutes from now → pre-alarm would be 30 min ago → null
        val triggerAtMillis = System.currentTimeMillis() + 30 * 60 * 1000
        val preAlarmTime = repo.computePreAlarmTime(triggerAtMillis)

        assertNull(preAlarmTime)
    }

    @Test
    fun `computePreAlarmTime returns null if alarm is in the past`() {
        val triggerAtMillis = System.currentTimeMillis() - 60 * 60 * 1000 // 1 hour ago
        val preAlarmTime = repo.computePreAlarmTime(triggerAtMillis)

        assertNull(preAlarmTime)
    }

    @Test
    fun `PRE_ALARM_OFFSET_MS is 60 minutes`() {
        assertEquals(60L * 60L * 1000L, AlarmRepository.PRE_ALARM_OFFSET_MS)
    }

    // ============== computeNextTriggerAfterToday (F5) ==============

    @Test
    fun `computeNextTriggerAfterToday starts from tomorrow`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val tomorrowDow = now.toLocalDate().plusDays(1).dayOfWeek.value

        val timeLabel = "08:00"
        val trigger = repo.computeNextTriggerAfterToday(timeLabel, listOf(tomorrowDow))
        assertNotNull(trigger)

        val triggerDate = LocalDate.ofInstant(trigger, zone)
        // Should be at least tomorrow
        assertTrue(triggerDate.toEpochDay() >= now.toLocalDate().plusDays(1).toEpochDay())
        assertEquals(tomorrowDow, triggerDate.dayOfWeek.value)
    }

    @Test
    fun `computeNextTriggerAfterToday returns null for empty days`() {
        assertNull(repo.computeNextTriggerAfterToday("08:00", emptyList()))
    }

    @Test
    fun `computeNextTriggerAfterToday wraps around week`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Pick a day 5 days from now
        val targetDow = now.toLocalDate().plusDays(5).dayOfWeek.value

        val trigger = repo.computeNextTriggerAfterToday("08:00", listOf(targetDow))
        assertNotNull(trigger)

        val triggerDate = LocalDate.ofInstant(trigger, zone)
        assertEquals(targetDow, triggerDate.dayOfWeek.value)
        // Should be between tomorrow and 8 days from now (search window is 0..7 from tomorrow)
        val daysFromNow = triggerDate.toEpochDay() - now.toLocalDate().toEpochDay()
        assertTrue(daysFromNow in 1..8)
    }

    // ============== Past time rejection ==============

    @Test
    fun `replaceForDate rejects past trigger time`() {
        // This test verifies that replaceForDate returns null for a past date
        val pastDate = LocalDate.now().minusDays(1)
        val result = repo.replaceForDate(pastDate, "08:00", "Test", "office")
        assertNull(result)
    }

    @Test
    fun `computeNextTrigger one-shot always returns future time`() {
        // Test with various times to ensure result is always in the future
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)

        for (hour in 0..23) {
            for (minute in listOf(0, 30)) {
                val timeLabel = String.format("%02d:%02d", hour, minute)
                val trigger = repo.computeNextTrigger(timeLabel, emptyList(), null)
                assertNotNull("Should return a trigger for $timeLabel", trigger)
                assertTrue("Trigger for $timeLabel should be in the future", trigger!!.toEpochMilli() > now.atZone(zone).toInstant().toEpochMilli())
            }
        }
    }
}
