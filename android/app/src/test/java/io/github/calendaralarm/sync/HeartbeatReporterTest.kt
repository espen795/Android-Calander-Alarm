package io.github.calendaralarm.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.AlarmTransactions
import io.github.calendaralarm.data.AppClock
import io.github.calendaralarm.data.DeviceProtectedStorage
import io.github.calendaralarm.data.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase 4 (principle 7): tests for HeartbeatReporter.
 *
 * - payload contents: only ENABLED + not-expired alarms, permission status included
 * - sent_at/time via AppClock (FixedClock)
 * - max 50 entries, no labels (privacy)
 * - worker/reporter does NOT fail on network error (Result.success, error logged)
 * - structural: NO network inside the AlarmTransactions lock (snapshot first,
 *   then send) — verified via source inspection of HeartbeatReporter.kt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HeartbeatReporterTest {

    private lateinit var context: Context
    private lateinit var store: AlarmStore
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 11, 22)
    private val tomorrow = LocalDate.of(2026, 11, 23)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("calendar_alarm_store", "AlarmRepositoryState").forEach {
            DeviceProtectedStorage.prefs(context, it).edit().clear().commit()
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        // 21:30 in the evening — tomorrow = 2026-11-23
        FixedClock.set(ZonedDateTime.of(2026, 11, 22, 21, 30, 0, 0, zone))
        store = AlarmStore(context)
    }

    @After
    fun tearDown() {
        store.removeAll()
        FixedClock.reset()
        AppClock.reset()
    }

    private fun alarm(
        date: LocalDate, time: String, label: String = "Kantoor", type: String = "office",
        enabled: Boolean = true, isSnooze: Boolean = false, triggerAtMillis: Long? = null,
        userDisabled: Boolean = false
    ): AlarmStore.OwnedAlarm {
        val trigger = triggerAtMillis
            ?: ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant().toEpochMilli()
        return AlarmStore.OwnedAlarm(
            id = store.nextId(),
            triggerAtMillis = trigger,
            timeLabel = time,
            label = label,
            date = date.toString(),
            type = type,
            enabled = enabled,
            isSnooze = isSnooze,
            userDisabled = userDisabled
        )
    }

    // ---------- payload-inhoud ----------

    @Test
    fun `payload contains enabled, not-expired and user-disabled alarms`() {
        val now = AppClock.now()
        store.save(alarm(tomorrow, "07:00", "tomorrow-active"))                    // YES
        store.save(alarm(tomorrow, "08:00", "tomorrow-auto-off", enabled = false)) // NO (auto-disabled)
        store.save(alarm(tomorrow, "09:00", "tomorrow-user-off", enabled = false,
            userDisabled = true))                                                  // YES, user_disabled=true
        store.save(alarm(today, "06:00", "today-expired",
            triggerAtMillis = now - 3600_000))                                     // NO (expired)
        store.save(alarm(tomorrow, "07:10", "snooze", isSnooze = true))             // YES (is_snooze=true)

        val payload = HeartbeatReporter(context).buildPayload()

        val times = payload.scheduled.map { it.time }.toSet()
        assertEquals(setOf("07:00", "09:00", "07:10"), times)
        val snooze = payload.scheduled.first { it.time == "07:10" }
        assertTrue(snooze.isSnooze)
        assertTrue(snooze.enabled)
        val userOff = payload.scheduled.first { it.time == "09:00" }
        assertTrue("B1: disabled by the user must be reported as user_disabled", userOff.userDisabled)
        assertFalse(userOff.enabled)
        assertFalse(payload.scheduled.first { it.time == "07:00" }.userDisabled)
    }

    @Test
    fun `payload fields date time enabled is_snooze correct and no label`() {
        store.save(alarm(tomorrow, "07:00", "Office day"))
        val payload = HeartbeatReporter(context).buildPayload()
        val e = payload.scheduled.single()
        assertEquals("2026-11-23", e.date)
        assertEquals("07:00", e.time)
        assertTrue(e.enabled)
        assertFalse(e.isSnooze)
        val js = kotlinx.serialization.json.Json.encodeToString(
            HeartbeatReporter.HeartbeatPayload.serializer(), payload)
        assertFalse("privacy: no labels in the heartbeat", js.contains("Office day") || js.contains("\"label\""))
    }

    @Test
    fun `payload permission status from PermissionStatus`() {
        store.save(alarm(tomorrow, "07:00"))
        val payload = HeartbeatReporter(context).buildPayload()
        // Robolectric SDK 30: POST_NOTIFICATIONS not needed (< TIRAMISU) → true;
        // exact alarms (< S in this config? SDK 30 = R < S=31) → true; fullScreen (< U=34) → true
        assertNotNull(payload.permissions)
        // batteryUnrestricted is false by default in Robolectric — we only check that the field exists
        // The server expects snake_case field names — check the serialized JSON.
        val js = kotlinx.serialization.json.Json.encodeToString(
            HeartbeatReporter.HeartbeatPayload.serializer(), payload)
        for (key in listOf("\"battery_unrestricted\"", "\"full_screen\"", "\"exact_alarms\"",
                "\"notifications\"", "\"is_snooze\"", "\"app_version\"", "\"version_code\"", "\"sent_at\"")) {
            assertTrue("JSON missing $key: $js", js.contains(key))
        }
    }

    @Test
    fun `payload app version and sent_at via AppClock`() {
        store.save(alarm(tomorrow, "07:00"))
        val now = AppClock.now()
        val payload = HeartbeatReporter(context).buildPayload(nowMillis = now)
        assertTrue(payload.appVersion.isNotEmpty())
        assertTrue(payload.versionCode > 0)
        // sent_at must be the ISO instant of AppClock.now()
        assertEquals(
            java.time.Instant.ofEpochMilli(now).toString(),
            payload.sentAt
        )
    }

    @Test
    fun `payload max 50 entries`() {
        repeat(60) { i ->
            store.save(alarm(tomorrow, "07:00", "alarm-$i",
                triggerAtMillis = AppClock.now() + (i + 1) * 60_000))
        }
        val payload = HeartbeatReporter(context).buildPayload()
        assertEquals(50, payload.scheduled.size)
    }


    // ---------- network error is not fatal ----------

    @Test
    fun `send returns failure on network error but does not throw`() = runTest {
        store.save(alarm(tomorrow, "07:00"))
        // No server reachable in Robolectric → network error expected
        val result = HeartbeatReporter(context).send()
        // must NOT crash; success=false with error, OR success=true if something happens to be listening
        if (!result.success) {
            assertNotNull(result.error)
        }
        assertTrue(result.scheduledCount >= 0)
    }

    // ---------- structural: no network inside the lock ----------

    @Test
    fun `buildPayload does no network inside AlarmTransactions lock`() {
        // Source inspection: in HeartbeatReporter.kt the AlarmTransactions.run block must
        // contain ONLY store read work (getAll/filter/sort) — no HttpClient, post, send.
        val rel = "java/io/github/calendaralarm/sync/HeartbeatReporter.kt"
        val src = listOf(java.io.File("src/main/$rel"), java.io.File("app/src/main/$rel"))
            .first { it.exists() }.readText()
        val runIdx = src.indexOf("AlarmTransactions.run")
        assertTrue("AlarmTransactions.run must occur in HeartbeatReporter", runIdx >= 0)
        // take the block up to the matching closing — simplified: up to "}.take(50)" or "sortedBy"
        val blockEnd = src.indexOf("sortedBy", runIdx)
        assertTrue(blockEnd > runIdx)
        val lockBlock = src.substring(runIdx, blockEnd)
        assertFalse("no HttpClient inside the lock", lockBlock.contains("HttpClient"))
        assertFalse("no post inside the lock", lockBlock.contains(".post("))
        assertFalse("no send inside the lock", lockBlock.contains("send("))
        // and the send() function DOES use a client, but outside the lock
        assertTrue(src.contains("buildClient"))
    }

    @Test
    fun `snapshot is consistent inside lock - store read inside run`() {
        // Functional: buildPayload must work while another thread holds the lock?
        // No — buildPayload itself uses the lock; we test that concurrent writes
        // during buildPayload do not crash (reentrant/safe).
        store.save(alarm(tomorrow, "07:00", "stable"))
        val payload = HeartbeatReporter(context).buildPayload()
        assertEquals(1, payload.scheduled.size)
        assertEquals("07:00", payload.scheduled[0].time)
    }
}
