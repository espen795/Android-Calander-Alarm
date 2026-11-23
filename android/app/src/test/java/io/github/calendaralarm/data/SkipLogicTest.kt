package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * F7 + F5: Unit tests for skip logic.
 *
 * Tests the skipCount lifecycle:
 * 1. User taps "Skip this time" → skipCount incremented
 * 2. Next schedule attempt → skipCount decremented, alarm NOT scheduled for today
 * 3. Alarm scheduled for next matching day instead
 * 4. Server sync (replaceForDate) → skipCount reset to 0
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SkipLogicTest {

    private lateinit var store: AlarmStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        store = AlarmStore(context)
    }

    @After
    fun tearDown() {
        store.removeAll()
    }

    private fun createRepeatAlarm(
        id: Int = 1,
        timeLabel: String = "07:00",
        repeatDays: List<Int> = listOf(1, 2, 3, 4, 5), // Mon-Fri
        skipCount: Int = 0,
        enabled: Boolean = true
    ): AlarmStore.OwnedAlarm {
        return AlarmStore.OwnedAlarm(
            id = id,
            triggerAtMillis = System.currentTimeMillis() + 86400000,
            timeLabel = timeLabel,
            label = "Repeat alarm",
            date = "2026-10-13",
            type = "manual",
            enabled = enabled,
            repeatDays = repeatDays,
            skipCount = skipCount
        )
    }

    // ============== Skip increment (user taps "Skip this time") ==============

    @Test
    fun `skip increments skipCount from 0 to 1`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        val updated = store.incrementSkipCount(1)
        assertNotNull(updated)
        assertEquals(1, updated!!.skipCount)
    }

    @Test
    fun `multiple skips accumulate`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        store.incrementSkipCount(1)
        store.incrementSkipCount(1)
        val updated = store.incrementSkipCount(1)

        assertNotNull(updated)
        assertEquals(3, updated!!.skipCount)
    }

    // ============== Skip decrement (consumed on next schedule) ==============

    @Test
    fun `skip decrement reduces skipCount by 1`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 2)
        store.save(alarm)

        val updated = store.decrementSkipCount(1)
        assertNotNull(updated)
        assertEquals(1, updated!!.skipCount)
    }

    @Test
    fun `skip decrement does not go below 0`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        val updated = store.decrementSkipCount(1)
        assertNotNull(updated)
        assertEquals(0, updated!!.skipCount)
    }

    @Test
    fun `skip decrement from 1 goes to 0`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 1)
        store.save(alarm)

        val updated = store.decrementSkipCount(1)
        assertNotNull(updated)
        assertEquals(0, updated!!.skipCount)
    }

    // ============== Skip reset (server sync) ==============

    @Test
    fun `resetSkipCount sets skipCount to 0`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 5)
        store.save(alarm)

        val updated = store.resetSkipCount(1)
        assertNotNull(updated)
        assertEquals(0, updated!!.skipCount)
    }

    @Test
    fun `resetSkipCount on 0 stays 0`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        val updated = store.resetSkipCount(1)
        assertNotNull(updated)
        assertEquals(0, updated!!.skipCount)
    }

    // ============== Full skip lifecycle simulation ==============

    @Test
    fun `full skip lifecycle increment then decrement`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        // User skips once
        var updated = store.incrementSkipCount(1)
        assertEquals(1, updated!!.skipCount)

        // User skips again
        updated = store.incrementSkipCount(1)
        assertEquals(2, updated!!.skipCount)

        // Next schedule: consume one skip
        updated = store.decrementSkipCount(1)
        assertEquals(1, updated!!.skipCount)

        // Next schedule: consume another skip
        updated = store.decrementSkipCount(1)
        assertEquals(0, updated!!.skipCount)

        // Next schedule: no more skips, alarm goes through
        val current = store.get(1)
        assertEquals(0, current!!.skipCount)
    }

    @Test
    fun `skip count persists across store instances`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarm = createRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)
        store.incrementSkipCount(1)
        store.incrementSkipCount(1)

        // Simulate process death + restart
        val newStore = AlarmStore(context)
        val retrieved = newStore.get(1)

        assertNotNull(retrieved)
        assertEquals(2, retrieved!!.skipCount)
    }

    // ============== Skip logic: repeatDays matching ==============

    @Test
    fun `repeat alarm with skipCount greater than 0 is identifiable`() {
        val alarm = createRepeatAlarm(
            id = 1,
            timeLabel = "07:00",
            repeatDays = listOf(1, 2, 3, 4, 5),
            skipCount = 1
        )
        store.save(alarm)

        // This simulates the check in scheduleOneShot:
        // Find existing alarm with same timeLabel + repeatDays + skipCount > 0
        val existing = store.getAll().firstOrNull {
            it.timeLabel == "07:00" &&
            it.repeatDays.sorted() == listOf(1, 2, 3, 4, 5).sorted() &&
            it.skipCount > 0 &&
            it.enabled
        }

        assertNotNull(existing)
        assertEquals(1, existing!!.id)
        assertEquals(1, existing.skipCount)
    }

    @Test
    fun `repeat alarm with skipCount 0 is not matched for skip`() {
        val alarm = createRepeatAlarm(
            id = 1,
            timeLabel = "07:00",
            repeatDays = listOf(1, 2, 3, 4, 5),
            skipCount = 0
        )
        store.save(alarm)

        val existing = store.getAll().firstOrNull {
            it.timeLabel == "07:00" &&
            it.repeatDays.sorted() == listOf(1, 2, 3, 4, 5).sorted() &&
            it.skipCount > 0 &&
            it.enabled
        }

        assertNull(existing)
    }

    @Test
    fun `one-shot alarm never matches skip logic`() {
        val alarm = createRepeatAlarm(
            id = 1,
            timeLabel = "07:00",
            repeatDays = emptyList(), // one-shot
            skipCount = 0
        )
        store.save(alarm)

        // One-shot alarms should not be found by skip logic (repeatDays is empty)
        val existing = store.getAll().firstOrNull {
            it.timeLabel == "07:00" &&
            it.repeatDays.isNotEmpty() && // skip logic only applies to repeat alarms
            it.skipCount > 0 &&
            it.enabled
        }

        assertNull(existing)
    }

    // ============== Skip + enabled state interaction ==============

    @Test
    fun `disabled alarm with skipCount is not matched`() {
        val alarm = createRepeatAlarm(
            id = 1,
            skipCount = 1,
            enabled = false // disabled
        )
        store.save(alarm)

        val existing = store.getAll().firstOrNull {
            it.timeLabel == "07:00" &&
            it.repeatDays.sorted() == listOf(1, 2, 3, 4, 5).sorted() &&
            it.skipCount > 0 &&
            it.enabled // only enabled alarms
        }

        assertNull(existing)
    }

    @Test
    fun `skipCount survives setEnabled toggle`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 3)
        store.save(alarm)

        // Disable and re-enable
        store.setEnabled(1, false)
        store.setEnabled(1, true)

        val retrieved = store.get(1)
        assertNotNull(retrieved)
        assertEquals(3, retrieved!!.skipCount)
        assertTrue(retrieved.enabled)
    }

    // ============== Edge cases ==============

    @Test
    fun `skip operations on non-existent alarm return null`() {
        assertNull(store.incrementSkipCount(999))
        assertNull(store.decrementSkipCount(999))
        assertNull(store.resetSkipCount(999))
    }

    @Test
    fun `skipCount is preserved when saving updated alarm`() {
        val alarm = createRepeatAlarm(id = 1, skipCount = 2)
        store.save(alarm)

        // Update other fields, skipCount should be preserved
        val updated = alarm.copy(label = "Updated label")
        store.save(updated)

        val retrieved = store.get(1)
        assertNotNull(retrieved)
        assertEquals(2, retrieved!!.skipCount)
        assertEquals("Updated label", retrieved.label)
    }
}
