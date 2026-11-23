package io.github.calendaralarm.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.data.AppClock
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
import java.time.ZonedDateTime

/**
 * P2A #6: the SYNC_FAILED notification logic as a pure function of (prefs, now).
 *
 * Rules (hardening plan phase 2, review #6):
 * - the counter is PERSISTENT (prefs) — a new worker instance sees the
 *   failures of earlier runs, so SYNC_FAILED IS notified;
 * - notify only after >= 3 CONSECUTIVE failed runs;
 * - after that max 1x per 12 hours (dedup kept);
 * - reset on success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncFailurePolicyTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences(SyncFailurePolicy.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, java.time.ZoneId.of("Europe/Amsterdam")))
    }

    @After
    fun tearDown() {
        FixedClock.reset()
        prefs.edit().clear().commit()
    }

    private fun now(): Long = AppClock.now()

    @Test
    fun `first two failures do not notify`() {
        val d1 = SyncFailurePolicy.onFailure(prefs, now(), "error 1")
        SyncFailurePolicy.record(prefs, d1)
        assertFalse(d1.notify)
        assertEquals(1, d1.newFailureCount)

        val d2 = SyncFailurePolicy.onFailure(prefs, now(), "error 2")
        assertFalse(d2.notify)
        assertEquals(2, d2.newFailureCount)
    }

    @Test
    fun `third consecutive failure notifies`() {
        SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error 1"))
        SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error 2"))
        val d3 = SyncFailurePolicy.onFailure(prefs, now(), "error 3")
        assertTrue("3rd consecutive failure must notify", d3.notify)
        assertEquals(3, d3.newFailureCount)
        assertEquals(now(), d3.newNotifiedAt)
    }

    @Test
    fun `within 12 hours after notification no new notification`() {
        repeat(3) { SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error")) }
        // 4th failure 1 hour later → silent
        FixedClock.advance(3_600_000L)
        val d4 = SyncFailurePolicy.onFailure(prefs, now(), "error 4")
        assertFalse("within 12h dedup → silent", d4.notify)
        assertEquals(4, d4.newFailureCount)
    }

    @Test
    fun `after 12 hours it notifies again`() {
        repeat(3) { SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error")) }
        FixedClock.advance(12L * 3_600_000L + 1)
        val d = SyncFailurePolicy.onFailure(prefs, now(), "error after 12h")
        assertTrue("after 12h notify again", d.notify)
        assertEquals(now(), d.newNotifiedAt)
    }

    @Test
    fun `success resets the counter so it takes 3 runs again`() {
        repeat(2) { SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error")) }
        SyncFailurePolicy.onSuccess(prefs)
        assertEquals(0, prefs.getInt(SyncFailurePolicy.KEY_CONSECUTIVE_FAILURES, 0))
        val d = SyncFailurePolicy.onFailure(prefs, now(), "new error after success")
        assertFalse(d.notify)
        assertEquals(1, d.newFailureCount)
    }

    @Test
    fun `success does NOT clear the 12h dedup marker (alternating fail-succeed)`() {
        // 3 failures → notified (notifiedAt = now)
        repeat(3) { SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error")) }
        val notifiedAt = prefs.getLong(SyncFailurePolicy.KEY_FAIL_NOTIFIED_AT, 0)
        assertTrue(notifiedAt > 0)
        // success → counter reset, but notifiedAt stays
        SyncFailurePolicy.onSuccess(prefs)
        assertEquals(0, prefs.getInt(SyncFailurePolicy.KEY_CONSECUTIVE_FAILURES, 0))
        assertEquals("12h dedup marker must NOT be cleared on success", notifiedAt, prefs.getLong(SyncFailurePolicy.KEY_FAIL_NOTIFIED_AT, 0))
        // after that 3 new failures within 12h → do NOT notify again (dedup still active)
        repeat(2) { SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error")) }
        val d3 = SyncFailurePolicy.onFailure(prefs, now(), "error 3 after success")
        assertFalse("within 12h of previous notification → silent, even with success in between", d3.notify)
    }

    @Test
    fun `counter survives new prefs instance (persistence across worker instances)`() {
        // Simulate: worker instance A fails 2x, "new" worker instance B (same prefs file) fails 1x → notify
        SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error 1"))
        SyncFailurePolicy.record(prefs, SyncFailurePolicy.onFailure(prefs, now(), "error 2"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshPrefs = context.getSharedPreferences(SyncFailurePolicy.PREFS_NAME, Context.MODE_PRIVATE)
        val d3 = SyncFailurePolicy.onFailure(freshPrefs, now(), "error 3 from new instance")
        assertTrue("counter must be persistent across worker instances", d3.notify)
    }
}
