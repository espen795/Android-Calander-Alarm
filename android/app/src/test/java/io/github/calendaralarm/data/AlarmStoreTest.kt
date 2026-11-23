package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * F7: Unit tests for AlarmStore.
 *
 * Tests: save/get/remove/getAll, nextId() monotonic, skipCount increment/decrement,
 * preAlarmId update, setEnabled toggle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmStoreTest {

    private lateinit var store: AlarmStore

    // P1 #1: AlarmStore now uses device-protected storage — tests must clear DP prefs
    private fun dpPrefs(context: Context) =
        DeviceProtectedStorage.prefs(context, "calendar_alarm_store")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear any existing prefs (both CE and DP)
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
        dpPrefs(context).edit().clear().commit()
        store = AlarmStore(context)
    }

    @After
    fun tearDown() {
        store.removeAll()
        dpPrefs(ApplicationProvider.getApplicationContext<Context>()).edit().clear().commit()
    }

    private fun createTestAlarm(
        id: Int = 1,
        timeLabel: String = "07:00",
        label: String = "Test alarm",
        enabled: Boolean = true,
        repeatDays: List<Int> = emptyList()
    ): AlarmStore.OwnedAlarm {
        return AlarmStore.OwnedAlarm(
            id = id,
            triggerAtMillis = System.currentTimeMillis() + 86400000, // tomorrow
            timeLabel = timeLabel,
            label = label,
            date = "2026-10-13",
            type = "manual",
            enabled = enabled,
            repeatDays = repeatDays
        )
    }

    // ============== save / get / remove / getAll ==============

    @Test
    fun `save and get alarm returns correct data`() {
        val alarm = createTestAlarm(id = 1, label = "Morning alarm")
        store.save(alarm)

        val retrieved = store.get(1)
        assertNotNull(retrieved)
        assertEquals("Morning alarm", retrieved!!.label)
        assertEquals("07:00", retrieved.timeLabel)
        assertTrue(retrieved.enabled)
    }

    @Test
    fun `get returns null for non-existent alarm`() {
        assertNull(store.get(999))
    }

    @Test
    fun `remove deletes alarm from store`() {
        val alarm = createTestAlarm(id = 1)
        store.save(alarm)
        assertNotNull(store.get(1))

        val removed = store.remove(1)
        assertNotNull(removed)
        assertEquals(1, removed!!.id)
        assertNull(store.get(1))
    }

    @Test
    fun `remove returns null for non-existent alarm`() {
        assertNull(store.remove(999))
    }

    @Test
    fun `getAll returns all alarms sorted by trigger time`() {
        val now = System.currentTimeMillis()
        val alarm1 = createTestAlarm(id = 1).copy(triggerAtMillis = now + 10000)
        val alarm2 = createTestAlarm(id = 2).copy(triggerAtMillis = now + 5000)
        val alarm3 = createTestAlarm(id = 3).copy(triggerAtMillis = now + 20000)

        store.save(alarm1)
        store.save(alarm2)
        store.save(alarm3)

        val all = store.getAll()
        assertEquals(3, all.size)
        // Should be sorted by triggerAtMillis ascending
        assertEquals(2, all[0].id) // earliest
        assertEquals(1, all[1].id)
        assertEquals(3, all[2].id) // latest
    }

    @Test
    fun `getEnabled returns only enabled alarms`() {
        store.save(createTestAlarm(id = 1, enabled = true))
        store.save(createTestAlarm(id = 2, enabled = false))
        store.save(createTestAlarm(id = 3, enabled = true))

        val enabled = store.getEnabled()
        assertEquals(2, enabled.size)
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun `removeAll clears all alarms and returns them`() {
        store.save(createTestAlarm(id = 1))
        store.save(createTestAlarm(id = 2))

        val removed = store.removeAll()
        assertEquals(2, removed.size)
        assertTrue(store.getAll().isEmpty())
    }

    // ============== nextId() monotonic ==============

    @Test
    fun `nextId returns sequential ids starting from 1`() {
        val id1 = store.nextId()
        val id2 = store.nextId()
        val id3 = store.nextId()

        assertEquals(1, id1)
        assertEquals(2, id2)
        assertEquals(3, id3)
    }

    @Test
    fun `nextId is monotonic across multiple calls`() {
        val ids = (1..10).map { store.nextId() }
        // Each id should be unique and increasing
        assertEquals(10, ids.toSet().size)
        assertEquals((1..10).toList(), ids)
    }

    @Test
    fun `nextId persists across store instances`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id1 = store.nextId()
        val id2 = store.nextId()

        // Create a new store instance (simulates app restart)
        val newStore = AlarmStore(context)
        val id3 = newStore.nextId()

        assertTrue("nextId should continue from persisted value", id3 > id2)
        assertEquals(id2 + 1, id3)
    }

    // ============== F5: skipCount logic ==============

    @Test
    fun `incrementSkipCount increases skip count by 1`() {
        val alarm = createTestAlarm(id = 1, repeatDays = listOf(1, 2, 3, 4, 5))
        store.save(alarm)
        assertEquals(0, store.get(1)!!.skipCount)

        val updated = store.incrementSkipCount(1)
        assertNotNull(updated)
        assertEquals(1, updated!!.skipCount)
        assertEquals(1, store.get(1)!!.skipCount)
    }

    @Test
    fun `incrementSkipCount multiple times accumulates`() {
        val alarm = createTestAlarm(id = 1, repeatDays = listOf(1, 2, 3, 4, 5))
        store.save(alarm)

        store.incrementSkipCount(1)
        store.incrementSkipCount(1)
        val updated = store.incrementSkipCount(1)

        assertNotNull(updated)
        assertEquals(3, updated!!.skipCount)
    }

    @Test
    fun `decrementSkipCount decreases skip count by 1`() {
        val alarm = createTestAlarm(id = 1, repeatDays = listOf(1, 2, 3, 4, 5))
            .copy(skipCount = 2)
        store.save(alarm)

        val updated = store.decrementSkipCount(1)
        assertNotNull(updated)
        assertEquals(1, updated!!.skipCount)
        assertEquals(1, store.get(1)!!.skipCount)
    }

    @Test
    fun `decrementSkipCount does not go below 0`() {
        val alarm = createTestAlarm(id = 1, repeatDays = listOf(1, 2, 3, 4, 5))
            .copy(skipCount = 0)
        store.save(alarm)

        val updated = store.decrementSkipCount(1)
        assertNotNull(updated)
        assertEquals(0, updated!!.skipCount)
    }

    @Test
    fun `resetSkipCount sets skip count to 0`() {
        val alarm = createTestAlarm(id = 1, repeatDays = listOf(1, 2, 3, 4, 5))
            .copy(skipCount = 5)
        store.save(alarm)

        val updated = store.resetSkipCount(1)
        assertNotNull(updated)
        assertEquals(0, updated!!.skipCount)
        assertEquals(0, store.get(1)!!.skipCount)
    }

    @Test
    fun `incrementSkipCount returns null for non-existent alarm`() {
        assertNull(store.incrementSkipCount(999))
    }

    @Test
    fun `decrementSkipCount returns null for non-existent alarm`() {
        assertNull(store.decrementSkipCount(999))
    }

    // ============== F4: preAlarmId ==============

    @Test
    fun `updatePreAlarmId sets pre-alarm request code`() {
        val alarm = createTestAlarm(id = 1)
        store.save(alarm)
        assertEquals(-1, store.get(1)!!.preAlarmId)

        val updated = store.updatePreAlarmId(1, 500001)
        assertNotNull(updated)
        assertEquals(500001, updated!!.preAlarmId)
        assertEquals(500001, store.get(1)!!.preAlarmId)
    }

    @Test
    fun `preAlarmId defaults to -1 for new alarms`() {
        val alarm = createTestAlarm(id = 1)
        assertEquals(-1, alarm.preAlarmId)
    }

    // ============== F1: setEnabled ==============

    @Test
    fun `setEnabled toggles alarm enabled state`() {
        val alarm = createTestAlarm(id = 1, enabled = true)
        store.save(alarm)

        val disabled = store.setEnabled(1, false)
        assertNotNull(disabled)
        assertFalse(disabled!!.enabled)
        assertFalse(store.get(1)!!.enabled)

        val enabled = store.setEnabled(1, true)
        assertNotNull(enabled)
        assertTrue(enabled!!.enabled)
        assertTrue(store.get(1)!!.enabled)
    }

    @Test
    fun `setEnabled returns null for non-existent alarm`() {
        assertNull(store.setEnabled(999, true))
    }

    // ============== Persistence (crash-safe commit) ==============

    @Test
    fun `data persists across store instances`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarm = createTestAlarm(id = 1, label = "Persisted alarm")
        store.save(alarm)

        // Create new store instance (simulates process death + restart)
        val newStore = AlarmStore(context)
        val retrieved = newStore.get(1)

        assertNotNull(retrieved)
        assertEquals("Persisted alarm", retrieved!!.label)
    }

    // ============== P1 review#2: corruptie-afhandeling ==============

    @Test
    fun `b3 corrupt main key is quarantined once, after which the store accepts saves again`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DeviceProtectedStorage.prefs(context, "calendar_alarm_store")
        val corrupt = """{"1":{"id":1,"triggerAtMillis":123,"timeLabel":"07:00" """
        prefs.edit().putString("alarms", corrupt).commit()

        val s = AlarmStore(context)
        // loadAll returns empty (fail-safe), but...
        assertTrue(s.getAll().isEmpty())
        // ...the raw blob is in quarantine (once — see B3)
        val quarantined = prefs.getString(AlarmStore.KEY_ALARMS_QUARANTINE, null)
        assertNotNull("corrupt blob must be quarantined", quarantined)
        assertEquals(corrupt, quarantined)

        // B3: repeated loads must NOT create extra quarantine copies
        s.getAll(); s.getAll(); s.get(1)
        val tsKeys = prefs.all.keys.filter { it.startsWith(AlarmStore.KEY_ALARMS_CORRUPT_PREFIX) }
        assertTrue("B3: max 1-3 quarantine copies, was ${tsKeys.size}", tsKeys.size in 1..3)

        // B3: after the quarantine (raw data is secured) persist may overwrite the main
        // key again — otherwise ALL saves would remain permanently refused and the
        // alarm chain breaks (AlarmManager is set, store is not).
        s.save(createTestAlarm(id = 99))
        val rawAfter = prefs.getString("alarms", null)
        assertNotNull("B3: after quarantine a save must be allowed to write the main key again", rawAfter)
        assertTrue("B3: new save is valid JSON with id=99", rawAfter!!.contains("\"id\":99"))
        assertEquals(1, s.getAll().size)
        assertEquals(99, s.getAll().first().id)
        // The quarantine blob is kept for recovery/analysis
        assertEquals(corrupt, prefs.getString(AlarmStore.KEY_ALARMS_QUARANTINE, null))
    }

    @Test
    fun `p1 valid backup is used on corruption of the main key`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DeviceProtectedStorage.prefs(context, "calendar_alarm_store")
        // First a valid save → backup is written along
        store.save(createTestAlarm(id = 1, label = "Backup alarm"))
        val backup = prefs.getString(AlarmStore.KEY_ALARMS_BACKUP, null)
        assertNotNull("on valid persist alarms_backup must be filled", backup)

        // Corrupt the main key
        prefs.edit().putString("alarms", "{kapot").commit()

        val s = AlarmStore(context)
        val all = s.getAll()
        assertEquals("on corruption the backup must serve as source", 1, all.size)
        assertEquals("Backup alarm", all.first().label)
    }

    @Test
    fun `p1 onCorruption callback is called once per process`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DeviceProtectedStorage.prefs(context, "calendar_alarm_store")
        prefs.edit().putString("alarms", "{kapot").commit()

        var calls = 0
        val prev = AlarmStore.onCorruption
        AlarmStore.onCorruption = { calls++ }
        try {
            val s = AlarmStore(context)
            s.getAll()
            s.getAll()
            assertEquals("corruption callback may only fire 1× per process", 1, calls)
        } finally {
            AlarmStore.onCorruption = prev
        }
    }
}
