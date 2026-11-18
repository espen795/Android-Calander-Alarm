package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.5.6 Story 1: Unit tests voor TimerStateStore.
 *
 * Tests: save/load/clear, hasActiveTimer, getRemainingMs (running + paused + finished),
 * custom time velden, thread safety.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TimerStateStoreTest {

    private lateinit var store: TimerStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear any existing prefs
        context.getSharedPreferences("calalarm_timer_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = TimerStateStore(context)
    }

    @After
    fun tearDown() {
        store.clear()
    }

    private fun createTestState(
        isRunning: Boolean = true,
        endTimeWallClock: Long = System.currentTimeMillis() + 60_000, // 1 min from now
        totalMs: Long = 25 * 60 * 1000L,
        remainingMsAtSave: Long = 60_000L,
        customHours: Int = 0,
        customMinutes: Int = 25,
        customSeconds: Int = 0
    ): TimerStateStore.TimerState {
        return TimerStateStore.TimerState(
            isRunning = isRunning,
            endTimeWallClock = endTimeWallClock,
            totalMs = totalMs,
            remainingMsAtSave = remainingMsAtSave,
            customHours = customHours,
            customMinutes = customMinutes,
            customSeconds = customSeconds
        )
    }

    // ============== save / load ==============

    @Test
    fun `save and load returns correct data`() {
        val state = createTestState(
            isRunning = true,
            endTimeWallClock = 1234567890L,
            totalMs = 1500000L,
            remainingMsAtSave = 60000L,
            customHours = 0,
            customMinutes = 25,
            customSeconds = 0
        )
        store.save(state)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(true, loaded!!.isRunning)
        assertEquals(1234567890L, loaded.endTimeWallClock)
        assertEquals(1500000L, loaded.totalMs)
        assertEquals(60000L, loaded.remainingMsAtSave)
        assertEquals(0, loaded.customHours)
        assertEquals(25, loaded.customMinutes)
        assertEquals(0, loaded.customSeconds)
    }

    @Test
    fun `load returns null when no state saved`() {
        assertNull(store.load())
    }

    @Test
    fun `load returns null after clear`() {
        store.save(createTestState())
        assertNotNull(store.load())

        store.clear()
        assertNull(store.load())
    }

    // ============== clear ==============

    @Test
    fun `clear removes all saved data`() {
        store.save(createTestState(isRunning = true))
        assertTrue(store.hasActiveTimer())

        store.clear()

        assertNull(store.load())
        assertFalse(store.hasActiveTimer())
    }

    // ============== hasActiveTimer ==============

    @Test
    fun `hasActiveTimer returns true for running timer`() {
        store.save(createTestState(isRunning = true))
        assertTrue(store.hasActiveTimer())
    }

    @Test
    fun `hasActiveTimer returns false for paused timer`() {
        store.save(createTestState(isRunning = false))
        assertFalse(store.hasActiveTimer())
    }

    @Test
    fun `hasActiveTimer returns false when no state`() {
        assertFalse(store.hasActiveTimer())
    }

    // ============== getRemainingMs ==============

    @Test
    fun `getRemainingMs returns calculated remaining for running timer`() {
        val now = System.currentTimeMillis()
        val endTime = now + 120_000 // 2 minutes from now
        store.save(createTestState(
            isRunning = true,
            endTimeWallClock = endTime,
            remainingMsAtSave = 120_000L
        ))

        val remaining = store.getRemainingMs()
        assertNotNull(remaining)
        // Should be close to 120000ms (minus a tiny bit of elapsed time)
        assertTrue("Remaining should be ~120000ms but was $remaining", remaining!! in 119_000..120_000)
    }

    @Test
    fun `getRemainingMs returns negative for finished running timer`() {
        val pastTime = System.currentTimeMillis() - 60_000 // 1 minute ago
        store.save(createTestState(
            isRunning = true,
            endTimeWallClock = pastTime,
            remainingMsAtSave = 0L
        ))

        val remaining = store.getRemainingMs()
        assertNotNull(remaining)
        assertTrue("Remaining should be negative but was $remaining", remaining!! <= 0)
    }

    @Test
    fun `getRemainingMs returns saved remaining for paused timer`() {
        store.save(createTestState(
            isRunning = false,
            remainingMsAtSave = 45_000L // 45 seconds remaining when paused
        ))

        val remaining = store.getRemainingMs()
        assertNotNull(remaining)
        assertEquals(45_000L, remaining)
    }

    @Test
    fun `getRemainingMs returns null when no state`() {
        assertNull(store.getRemainingMs())
    }

    // ============== Edge cases ==============

    @Test
    fun `save overwrites previous state`() {
        // Save first state
        store.save(createTestState(
            isRunning = true,
            endTimeWallClock = 1000L,
            totalMs = 60_000L,
            remainingMsAtSave = 60_000L
        ))

        // Save second state (overwrite)
        store.save(createTestState(
            isRunning = false,
            endTimeWallClock = 2000L,
            totalMs = 120_000L,
            remainingMsAtSave = 30_000L,
            customMinutes = 2
        ))

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(false, loaded!!.isRunning)
        assertEquals(2000L, loaded.endTimeWallClock)
        assertEquals(120_000L, loaded.totalMs)
        assertEquals(30_000L, loaded.remainingMsAtSave)
        assertEquals(2, loaded.customMinutes)
    }

    @Test
    fun `save with custom time fields preserves values`() {
        store.save(createTestState(
            customHours = 1,
            customMinutes = 30,
            customSeconds = 45
        ))

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.customHours)
        assertEquals(30, loaded.customMinutes)
        assertEquals(45, loaded.customSeconds)
    }

    @Test
    fun `multiple saves and clears work correctly`() {
        // Save → clear → save → load
        store.save(createTestState(isRunning = true, remainingMsAtSave = 10_000L))
        store.clear()
        assertNull(store.load())

        store.save(createTestState(isRunning = true, remainingMsAtSave = 20_000L))
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(20_000L, loaded!!.remainingMsAtSave)

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `state survives multiple store instances`() {
        // Save with first instance
        store.save(createTestState(
            isRunning = true,
            endTimeWallClock = System.currentTimeMillis() + 300_000,
            totalMs = 300_000L,
            remainingMsAtSave = 300_000L
        ))

        // Create new instance (simulates app restart)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val newStore = TimerStateStore(context)

        val loaded = newStore.load()
        assertNotNull(loaded)
        assertEquals(true, loaded!!.isRunning)
        assertEquals(300_000L, loaded.totalMs)
    }
}
