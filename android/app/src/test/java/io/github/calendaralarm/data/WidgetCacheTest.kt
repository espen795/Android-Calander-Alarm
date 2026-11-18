package io.github.calendaralarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests voor WidgetCache — formatNextAlarm() logica.
 *
 * Test de Samsung-compatible alarm tekst formatting zonder Android dependencies.
 */
class WidgetCacheTest {

    @Test
    fun `formatNextAlarm returns default text when alarm is null`() {
        val result = WidgetCache.formatNextAlarm(null)
        assertEquals("No alarm scheduled", result)
    }

    @Test
    fun `formatNextAlarm returns default text when alarm is disabled`() {
        val alarm = createTestAlarm(enabled = false)
        val result = WidgetCache.formatNextAlarm(alarm)
        assertEquals("No alarm scheduled", result)
    }

    @Test
    fun `formatNextAlarm returns default text when alarm is in the past`() {
        val pastTime = System.currentTimeMillis() - 60_000 // 1 minute ago
        val alarm = createTestAlarm(triggerAtMillis = pastTime, enabled = true)
        val result = WidgetCache.formatNextAlarm(alarm)
        assertEquals("No alarm scheduled", result)
    }

    @Test
    fun `formatNextAlarm shows vandaag for alarm today`() {
        // Alarm over 2 uur vanaf nu (zeker vandaag)
        val twoHoursFromNow = System.currentTimeMillis() + (2 * 60 * 60 * 1000)
        val alarm = createTestAlarm(
            triggerAtMillis = twoHoursFromNow,
            timeLabel = "14:30",
            label = "Test Alarm",
            type = "manual",
            enabled = true
        )
        val result = WidgetCache.formatNextAlarm(alarm)
        assertTrue("Should contain 'today': $result", result.contains("today"))
        assertTrue("Should contain time: $result", result.contains("14:30"))
        assertTrue("Should contain label: $result", result.contains("Test Alarm"))
    }

    @Test
    fun `formatNextAlarm shows morgen for alarm tomorrow`() {
        // Alarm morgen zelfde tijd
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 8)
        cal.set(Calendar.MINUTE, 0)

        val alarm = createTestAlarm(
            triggerAtMillis = cal.timeInMillis,
            timeLabel = "08:00",
            label = "Morning Alarm",
            type = "office",
            enabled = true
        )
        val result = WidgetCache.formatNextAlarm(alarm)
        assertTrue("Should contain 'tomorrow': $result", result.contains("tomorrow"))
        assertTrue("Should contain office emoji: $result", result.contains("🏢"))
    }

    @Test
    fun `formatNextAlarm shows weekday for alarm later this week`() {
        // Alarm over 3 dagen
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 3)
        cal.set(Calendar.HOUR_OF_DAY, 7)
        cal.set(Calendar.MINUTE, 30)

        val alarm = createTestAlarm(
            triggerAtMillis = cal.timeInMillis,
            timeLabel = "07:30",
            label = "Future Alarm",
            type = "home",
            enabled = true
        )
        val result = WidgetCache.formatNextAlarm(alarm)
        // Should NOT contain "vandaag" or "morgen"
        assertTrue("Should NOT contain 'today': $result", !result.contains("today"))
        assertTrue("Should NOT contain 'tomorrow': $result", !result.contains("tomorrow"))
        // Should contain home emoji
        assertTrue("Should contain home emoji: $result", result.contains("🏠"))
    }

    @Test
    fun `formatNextAlarm uses correct emoji for alarm type`() {
        val futureTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000)

        val officeAlarm = createTestAlarm(futureTime, "09:00", "Office", "office", true)
        assertTrue(WidgetCache.formatNextAlarm(officeAlarm).contains("🏢"))

        val homeAlarm = createTestAlarm(futureTime, "09:00", "Home", "home", true)
        assertTrue(WidgetCache.formatNextAlarm(homeAlarm).contains("🏠"))

        val offAlarm = createTestAlarm(futureTime, "09:00", "Off", "off", true)
        assertTrue(WidgetCache.formatNextAlarm(offAlarm).contains("😴"))

        val testAlarm = createTestAlarm(futureTime, "09:00", "Test", "test", true)
        assertTrue(WidgetCache.formatNextAlarm(testAlarm).contains("🧪"))

        val manualAlarm = createTestAlarm(futureTime, "09:00", "Manual", "manual", true)
        assertTrue(WidgetCache.formatNextAlarm(manualAlarm).contains("⏰"))
    }

    @Test
    fun `formatNextAlarm format is consistent`() {
        val futureTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000)
        val alarm = createTestAlarm(futureTime, "06:45", "Kantoor", "office", true)

        val result = WidgetCache.formatNextAlarm(alarm)

        // Format: "{emoji} {day} {time} — {label}"
        // Example: "🏢 vandaag 06:45 — Kantoor"
        assertTrue("Should match expected format: $result",
            result.matches(Regex("^🏢 (today|tomorrow|\\p{L}{2}) 06:45 — Kantoor$")))
    }

    // ============== Helper ==============

    private fun createTestAlarm(
        triggerAtMillis: Long = System.currentTimeMillis() + 3_600_000,
        timeLabel: String = "07:00",
        label: String = "Test",
        type: String = "manual",
        enabled: Boolean = true
    ): AlarmStore.OwnedAlarm {
        return AlarmStore.OwnedAlarm(
            id = 1,
            triggerAtMillis = triggerAtMillis,
            timeLabel = timeLabel,
            label = label,
            date = "2026-10-15",
            type = type,
            enabled = enabled
        )
    }
}
