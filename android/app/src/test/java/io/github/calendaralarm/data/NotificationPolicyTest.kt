package io.github.calendaralarm.data

import android.content.Context
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.service.AlarmNotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.6.12: Unit tests for the notification policy (design notification-policy-2026-11-12).
 *
 * Tested scenarios (state-key dedup in AlarmRepository):
 * 1. Same values scheduled again → NO notification (idempotent, "still 10").
 * 2. Changed values (time/label/enabled) → notification.
 * 3. force=true (boot/system reschedule) → NEVER a notification, also on "change".
 * 4. State key contains no triggerAtMillis → reschedule of repeat alarm with
 *    shifted triggerAtMillis but same timeLabel/days/label/enabled = silent.
 * 5. cancel() of existing alarm → REMOVED notification; cancel(force=true) → silent.
 * 6. Central policy funnel: policyAllows() decision table.
 *
 * v2.6.13 (recon L1–L9):
 * 7. replaceForDate identical plan 2× → 0 notifications, ID stable (L1 idempotent).
 * 8. replaceForDate changed plan → repo silent, ID reused; worker reports 1× (L1).
 * 9. cancelForDate(force=true) → silent (L4).
 * 10. Funnel content dedup: identical SET 2× → 1st true, 2nd false (L9 safety net).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NotificationPolicyTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository

    private fun notificationCount(): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).allNotifications.size
    }

    private fun clearNotifications() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }

    // v2.6.13 L9: clear the funnel content-dedup state (between "bursts")
    // P1 #1: funnel lives in device-protected storage
    private fun clearFunnel() {
        context.getSharedPreferences("notif_funnel", Context.MODE_PRIVATE).edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "notif_funnel").edit().clear().commit()
    }

    private fun makeAlarm(
        id: Int = 1,
        triggerAtMillis: Long = System.currentTimeMillis() + 3_600_000,
        timeLabel: String = "07:30",
        label: String = "Kantoor",
        date: String = "2026-11-13",
        enabled: Boolean = true,
        repeatDays: List<Int> = emptyList()
    ) = AlarmStore.OwnedAlarm(
        id = id,
        triggerAtMillis = triggerAtMillis,
        timeLabel = timeLabel,
        label = label,
        date = date,
        type = "office",
        enabled = enabled,
        repeatDays = repeatDays
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        // P1 #1: also clear DP prefs
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        clearFunnel()  // v2.6.13 L9
        repo = AlarmRepository(context)
    }

    @After
    fun tearDown() {
        repo.store.removeAll()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        clearFunnel()  // v2.6.13 L9
    }

    // ============== State key ==============

    @Test
    fun `state key excludes triggerAtMillis so reschedule of repeat alarm is identical`() {
        val a = makeAlarm(triggerAtMillis = 1000L, repeatDays = listOf(1, 2, 3, 4, 5))
        val b = makeAlarm(triggerAtMillis = 999_999_999L, repeatDays = listOf(5, 3, 1, 4, 2)) // same days, different order + different trigger
        assertEquals(repo.alarmStateKey(a), repo.alarmStateKey(b))
    }

    @Test
    fun `state key changes when timeLabel label or enabled changes`() {
        val base = makeAlarm()
        assertTrue(repo.alarmStateKey(base) != repo.alarmStateKey(base.copy(timeLabel = "08:00")))
        assertTrue(repo.alarmStateKey(base) != repo.alarmStateKey(base.copy(label = "Home")))
        assertTrue(repo.alarmStateKey(base) != repo.alarmStateKey(base.copy(enabled = false)))
    }

    // ============== Dedup: same values → silent ==============

    @Test
    fun `schedule same values twice produces only one notification`() {
        val alarm = makeAlarm()
        repo.schedule(alarm)
        clearNotifications()
        repo.schedule(alarm) // identical — e.g. sync re-confirms plan
        assertEquals("Second identical schedule must not notify", 0, notificationCount())
    }

    @Test
    fun `reschedule repeat alarm with shifted triggerAtMillis is silent`() {
        // boot-reschedule scenario: same timeLabel/days/label/enabled, different triggerAtMillis
        val day1 = makeAlarm(triggerAtMillis = System.currentTimeMillis() + 3_600_000, repeatDays = listOf(1, 2, 3, 4, 5))
        repo.schedule(day1)
        clearNotifications()
        val day2 = day1.copy(triggerAtMillis = System.currentTimeMillis() + 8 * 24 * 3_600_000L)
        repo.schedule(day2) // NOT force — simulates sync pushing same plan with new trigger
        assertEquals("Repeat reschedule with same content must be silent", 0, notificationCount())
    }

    // ============== Changed → notification ==============

    @Test
    fun `schedule changed time produces a notification`() {
        repo.schedule(makeAlarm(timeLabel = "07:30"))
        clearNotifications()
        repo.schedule(makeAlarm(timeLabel = "06:45"))
        assertEquals("Changed time must give 1 notification", 1, notificationCount())
    }

    @Test
    fun `setEnabled disable produces a notification once`() {
        repo.schedule(makeAlarm())
        clearNotifications()
        clearFunnel()  // v2.6.13 L9: identical CHANGED|time|label within one burst is silent (safety net)
        repo.setEnabled(1, false)
        assertEquals("Disable must give 1 notification", 1, notificationCount())
        // Toggle again to same state via setEnabled(false) → store state does not change, but key does not change → silent
        clearNotifications()
        repo.setEnabled(1, false)
        assertEquals("Repeated disable must be silent", 0, notificationCount())
    }

    // ============== force=true → silent (boot/system) ==============

    @Test
    fun `schedule with force=true never notifies even on change`() {
        repo.schedule(makeAlarm(timeLabel = "07:30"), force = true)
        assertEquals("force=true (boot) must not notify", 0, notificationCount())
        repo.schedule(makeAlarm(timeLabel = "06:00"), force = true) // "change" but force
        assertEquals("force=true must also be silent on change", 0, notificationCount())
    }

    @Test
    fun `rescheduleAll after boot is silent`() {
        // Simulate: alarm exists in store, boot → rescheduleAll (force=true internally)
        repo.schedule(makeAlarm(), force = true)
        assertEquals(0, notificationCount())
        repo.rescheduleAll()
        assertEquals("rescheduleAll (boot) must be silent", 0, notificationCount())
    }

    // ============== cancel() ==============

    @Test
    fun `cancel existing alarm notifies removed once`() {
        repo.schedule(makeAlarm())
        clearNotifications()
        repo.cancel(1)
        assertEquals("Removing existing alarm must give 1 notification", 1, notificationCount())
    }

    @Test
    fun `cancel non-existing alarm is silent`() {
        val result = repo.cancel(999)
        assertFalse(result)
        assertEquals("Cancel of non-existing alarm must not notify", 0, notificationCount())
    }

    @Test
    fun `cancel with force=true is silent`() {
        repo.schedule(makeAlarm(), force = true)
        clearNotifications()
        repo.cancel(1, force = true)
        assertEquals("force-cancel (system) must be silent", 0, notificationCount())
    }

    // ============== Central policy ==============

    @Test
    fun `policy allows SET CHANGED REMOVED PRE_ALARM RINGING SYNC_FAILED UPDATE`() {
        AlarmNotificationHelper.AlarmEvent.values().forEach { event ->
            assertTrue("Event $event must be allowed", AlarmNotificationHelper.policyAllows(event))
        }
    }

    // ============== v2.6.13 L1: replaceForDate idempotent + ID-stable + silent ==============

    @Test
    fun `replaceForDate identical plan twice produces zero notifications and stable id`() {
        val date = java.time.LocalDate.now().plusDays(2)  // always in the future
        val first = repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        assertTrue("First replaceForDate must succeed", first != null)
        clearNotifications()
        val second = repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        assertEquals("Identical plan 2× → 0 notifications (L1)", 0, notificationCount())
        assertEquals("ID must stay stable on identical plan (L1)", first!!.id, second!!.id)
    }

    @Test
    fun `replaceForDate changed plan is silent from repo keeps id and worker would report one`() {
        val date = java.time.LocalDate.now().plusDays(2)
        val first = repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)!!
        clearNotifications()

        // Simulate the worker logic (L1): compute event BEFORE apply
        val dateStr = date.toString()
        val existingForDate = repo.store.getAll().filter { it.date == dateStr && it.type != "manual" && it.type != "test" }
        val event = when {
            existingForDate.none { it.enabled } -> AlarmNotificationHelper.AlarmEvent.SET
            existingForDate.any { it.timeLabel != "08:00" || it.label != "Kantoor" } ->
                AlarmNotificationHelper.AlarmEvent.CHANGED
            else -> null
        }
        val scheduled = repo.replaceForDate(date, "08:00", "Kantoor", "office", silent = true)

        // Repo itself notifies NOTHING (silent=true) — the worker is the only notifier
        assertEquals("replaceForDate(silent=true) must not notify itself", 0, notificationCount())
        assertEquals("ID must be reused on changed plan (L1)", first.id, scheduled!!.id)
        assertEquals("Worker computes CHANGED on time change", AlarmNotificationHelper.AlarmEvent.CHANGED, event)

        // Worker notifies 1× via the funnel
        event?.let {
            AlarmNotificationHelper.notifyAlarmEvent(context, it, time = "08:00", label = "Kantoor", alarmId = scheduled.id)
        }
        assertEquals("Worker notifies exactly 1× on change (L1)", 1, notificationCount())
    }

    @Test
    fun `replaceForDate identical plan worker event is null so no notification`() {
        val date = java.time.LocalDate.now().plusDays(2)
        repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        clearNotifications()

        val dateStr = date.toString()
        val existingForDate = repo.store.getAll().filter { it.date == dateStr && it.type != "manual" && it.type != "test" }
        val event = when {
            existingForDate.none { it.enabled } -> AlarmNotificationHelper.AlarmEvent.SET
            existingForDate.any { it.timeLabel != "07:30" || it.label != "Kantoor" } ->
                AlarmNotificationHelper.AlarmEvent.CHANGED
            else -> null
        }
        val scheduled = repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        assertEquals("Identical plan → worker event = null", null, event)
        event?.let {
            AlarmNotificationHelper.notifyAlarmEvent(context, it, time = "07:30", label = "Kantoor", alarmId = scheduled!!.id)
        }
        assertEquals("Identical re-sync → 0 notifications", 0, notificationCount())
    }

    // ============== v2.6.13 L4: cancelForDate force=true → silent ==============

    @Test
    fun `cancelForDate with force=true is silent but cancels`() {
        val date = java.time.LocalDate.now().plusDays(2)
        repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        clearNotifications()
        val count = repo.cancelForDate(date, force = true)
        assertEquals("There was 1 smart alarm for that date", 1, count)
        assertEquals("cancelForDate(force=true) → silent (worker reports aggregate) (L4)", 0, notificationCount())
    }

    // ============== v2.6.13 L9: funnel content-dedup (safety net) ==============

    @Test
    fun `funnel content dedup identical CHANGED twice returns false second time`() {
        // NB: CHANGED uses hardcoded strings (no R.string), so this path really
        // posts a notification under Robolectric — SET/UPDATE would
        // throw Resources$NotFoundException (resources not loaded in this config).
        val r1 = AlarmNotificationHelper.notifyAlarmEvent(
            context, AlarmNotificationHelper.AlarmEvent.CHANGED, time = "07:30", label = "Kantoor", alarmId = 1
        )
        assertTrue("First CHANGED must be notified (dedup passes)", r1)
        assertEquals(1, notificationCount())
        val r2 = AlarmNotificationHelper.notifyAlarmEvent(
            context, AlarmNotificationHelper.AlarmEvent.CHANGED, time = "07:30", label = "Kantoor", alarmId = 1
        )
        assertFalse("Identical CHANGED (same event|time|label) must be silent (L9)", r2)
        assertEquals("Dedup block posts nothing new", 1, notificationCount())

        // Different content → dedup passes (return value is the assertion; the
        // physical count differs per notification ID and is not relevant here)
        val r3 = AlarmNotificationHelper.notifyAlarmEvent(
            context, AlarmNotificationHelper.AlarmEvent.CHANGED, time = "08:00", label = "Kantoor", alarmId = 2
        )
        assertTrue("CHANGED with different time must be notified (dedup passes)", r3)
    }
}
