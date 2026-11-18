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
 * v2.6.12: Unit tests voor de notificatie-policy (design notification-policy-2026-11-12).
 *
 * Geteste scenario's (state-key dedup in AlarmRepository):
 * 1. Zelfde waarden opnieuw schedule() → GEEN melding (idempotent, "nog steeds op 10").
 * 2. Gewijzigde waarden (tijd/label/enabled) → WEL melding.
 * 3. force=true (boot/system reschedule) → NOOIT melding, ook bij "wijziging".
 * 4. State-key bevat géén triggerAtMillis → reschedule van repeat-alarm met
 *    verschoven triggerAtMillis maarzelfde timeLabel/days/label/enabled = stil.
 * 5. cancel() van bestaand alarm → REMOVED melding; cancel(force=true) → stil.
 * 6. Centrale policy-funnel: policyAllows() decision table.
 *
 * v2.6.13 (recon L1–L9):
 * 7. replaceForDate identiek plan 2× → 0 meldingen, ID stabiel (L1 idempotent).
 * 8. replaceForDate gewijzigd plan → repo stil, ID hergebruikt; worker meldt 1× (L1).
 * 9. cancelForDate(force=true) → stil (L4).
 * 10. Funnel content-dedup: identieke SET 2× → 1e true, 2e false (L9 vangnet).
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

    // v2.6.13 L9: wis de funnel content-dedup state (tussen "bursts" door)
    private fun clearFunnel() {
        context.getSharedPreferences("notif_funnel", Context.MODE_PRIVATE).edit().clear().commit()
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
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        clearFunnel()  // v2.6.13 L9
        repo = AlarmRepository(context)
    }

    @After
    fun tearDown() {
        repo.store.removeAll()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        clearFunnel()  // v2.6.13 L9
    }

    // ============== State-key ==============

    @Test
    fun `state key excludes triggerAtMillis so reschedule of repeat alarm is identical`() {
        val a = makeAlarm(triggerAtMillis = 1000L, repeatDays = listOf(1, 2, 3, 4, 5))
        val b = makeAlarm(triggerAtMillis = 999_999_999L, repeatDays = listOf(5, 3, 1, 4, 2)) // zelfde dagen, andere volgorde + andere trigger
        assertEquals(repo.alarmStateKey(a), repo.alarmStateKey(b))
    }

    @Test
    fun `state key changes when timeLabel label or enabled changes`() {
        val base = makeAlarm()
        assertTrue(repo.alarmStateKey(base) != repo.alarmStateKey(base.copy(timeLabel = "08:00")))
        assertTrue(repo.alarmStateKey(base) != repo.alarmStateKey(base.copy(label = "Thuis")))
        assertTrue(repo.alarmStateKey(base) != repo.alarmStateKey(base.copy(enabled = false)))
    }

    // ============== Dedup: zelfde waarden → stil ==============

    @Test
    fun `schedule same values twice produces only one notification`() {
        val alarm = makeAlarm()
        repo.schedule(alarm)
        clearNotifications()
        repo.schedule(alarm) // identiek — bv. sync herbevestigt plan
        assertEquals("Tweede identieke schedule mag geen melding geven", 0, notificationCount())
    }

    @Test
    fun `reschedule repeat alarm with shifted triggerAtMillis is silent`() {
        // boot-reschedule scenario: zelfde timeLabel/days/label/enabled, andere triggerAtMillis
        val day1 = makeAlarm(triggerAtMillis = System.currentTimeMillis() + 3_600_000, repeatDays = listOf(1, 2, 3, 4, 5))
        repo.schedule(day1)
        clearNotifications()
        val day2 = day1.copy(triggerAtMillis = System.currentTimeMillis() + 8 * 24 * 3_600_000L)
        repo.schedule(day2) // NIET force — simuleert sync die zelfde plan pusht met nieuwe trigger
        assertEquals("Repeat-reschedule met zelfde inhoud moet stil zijn", 0, notificationCount())
    }

    // ============== Gewijzigd → melding ==============

    @Test
    fun `schedule changed time produces a notification`() {
        repo.schedule(makeAlarm(timeLabel = "07:30"))
        clearNotifications()
        repo.schedule(makeAlarm(timeLabel = "06:45"))
        assertEquals("Gewijzigde tijd moet 1 melding geven", 1, notificationCount())
    }

    @Test
    fun `setEnabled disable produces a notification once`() {
        repo.schedule(makeAlarm())
        clearNotifications()
        clearFunnel()  // v2.6.13 L9: identieke CHANGED|time|label binnen één burst is stil (vangnet)
        repo.setEnabled(1, false)
        assertEquals("Uitzetten moet 1 melding geven", 1, notificationCount())
        // Nogmaals toggelen naar zelfde stand via setEnabled(false) → store state ändert niet, maar key wijzigt niet → stil
        clearNotifications()
        repo.setEnabled(1, false)
        assertEquals("Herhaald uitzetten moet stil zijn", 0, notificationCount())
    }

    // ============== force=true → stil (boot/system) ==============

    @Test
    fun `schedule with force=true never notifies even on change`() {
        repo.schedule(makeAlarm(timeLabel = "07:30"), force = true)
        assertEquals("force=true (boot) mag geen melding geven", 0, notificationCount())
        repo.schedule(makeAlarm(timeLabel = "06:00"), force = true) // "wijziging" maar force
        assertEquals("force=true moet ook bij wijziging stil zijn", 0, notificationCount())
    }

    @Test
    fun `rescheduleAll after boot is silent`() {
        // Simuleer: alarm bestaat in store, boot → rescheduleAll (force=true intern)
        repo.schedule(makeAlarm(), force = true)
        assertEquals(0, notificationCount())
        repo.rescheduleAll()
        assertEquals("rescheduleAll (boot) moet stil zijn", 0, notificationCount())
    }

    // ============== cancel() ==============

    @Test
    fun `cancel existing alarm notifies removed once`() {
        repo.schedule(makeAlarm())
        clearNotifications()
        repo.cancel(1)
        assertEquals("Verwijderen van bestaand alarm moet 1 melding geven", 1, notificationCount())
    }

    @Test
    fun `cancel non-existing alarm is silent`() {
        val result = repo.cancel(999)
        assertFalse(result)
        assertEquals("Cancel van onbestaand alarm mag geen melding geven", 0, notificationCount())
    }

    @Test
    fun `cancel with force=true is silent`() {
        repo.schedule(makeAlarm(), force = true)
        clearNotifications()
        repo.cancel(1, force = true)
        assertEquals("force-cancel (system) moet stil zijn", 0, notificationCount())
    }

    // ============== Centrale policy ==============

    @Test
    fun `policy allows SET CHANGED REMOVED PRE_ALARM RINGING SYNC_FAILED UPDATE`() {
        AlarmNotificationHelper.AlarmEvent.values().forEach { event ->
            assertTrue("Event $event moet toegestaan zijn", AlarmNotificationHelper.policyAllows(event))
        }
    }

    // ============== v2.6.13 L1: replaceForDate idempotent + ID-stabiel + stil ==============

    @Test
    fun `replaceForDate identical plan twice produces zero notifications and stable id`() {
        val date = java.time.LocalDate.now().plusDays(2)  // altijd in de toekomst
        val first = repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        assertTrue("Eerste replaceForDate moet lukken", first != null)
        clearNotifications()
        val second = repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        assertEquals("Identiek plan 2× → 0 meldingen (L1)", 0, notificationCount())
        assertEquals("ID moet stabiel blijven bij identiek plan (L1)", first!!.id, second!!.id)
    }

    @Test
    fun `replaceForDate changed plan is silent from repo keeps id and worker would report one`() {
        val date = java.time.LocalDate.now().plusDays(2)
        val first = repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)!!
        clearNotifications()

        // Simuleer de worker-logica (L1): event berekenen VÓÓR apply
        val dateStr = date.toString()
        val existingForDate = repo.store.getAll().filter { it.date == dateStr && it.type != "manual" && it.type != "test" }
        val event = when {
            existingForDate.none { it.enabled } -> AlarmNotificationHelper.AlarmEvent.SET
            existingForDate.any { it.timeLabel != "08:00" || it.label != "Kantoor" } ->
                AlarmNotificationHelper.AlarmEvent.CHANGED
            else -> null
        }
        val scheduled = repo.replaceForDate(date, "08:00", "Kantoor", "office", silent = true)

        // Repo zelf meldt NIETS (silent=true) — de worker is de enige melder
        assertEquals("replaceForDate(silent=true) mag zelf geen melding geven", 0, notificationCount())
        assertEquals("ID moet hergebruikt worden bij gewijzigd plan (L1)", first.id, scheduled!!.id)
        assertEquals("Worker berekent CHANGED bij tijd-wijziging", AlarmNotificationHelper.AlarmEvent.CHANGED, event)

        // Worker meldt 1× via de funnel
        event?.let {
            AlarmNotificationHelper.notifyAlarmEvent(context, it, time = "08:00", label = "Kantoor", alarmId = scheduled.id)
        }
        assertEquals("Worker meldt exact 1× bij wijziging (L1)", 1, notificationCount())
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
        assertEquals("Identiek plan → worker event = null", null, event)
        event?.let {
            AlarmNotificationHelper.notifyAlarmEvent(context, it, time = "07:30", label = "Kantoor", alarmId = scheduled!!.id)
        }
        assertEquals("Identieke her-sync → 0 meldingen", 0, notificationCount())
    }

    // ============== v2.6.13 L4: cancelForDate force=true → stil ==============

    @Test
    fun `cancelForDate with force=true is silent but cancels`() {
        val date = java.time.LocalDate.now().plusDays(2)
        repo.replaceForDate(date, "07:30", "Kantoor", "office", silent = true)
        clearNotifications()
        val count = repo.cancelForDate(date, force = true)
        assertEquals("Er was 1 smart-alarm voor die datum", 1, count)
        assertEquals("cancelForDate(force=true) → stil (worker meldt aggregaat) (L4)", 0, notificationCount())
    }

    // ============== v2.6.13 L9: funnel content-dedup (vangnet) ==============

    @Test
    fun `funnel content dedup identical CHANGED twice returns false second time`() {
        // NB: CHANGED gebruikt hardcoded strings (geen R.string), dus dit pad post ook
        // daadwerkelijk een notificatie onder Robolectric — SET/UPDATE zouden
        // Resources$NotFoundException geven (resources niet geladen in deze config).
        val r1 = AlarmNotificationHelper.notifyAlarmEvent(
            context, AlarmNotificationHelper.AlarmEvent.CHANGED, time = "07:30", label = "Kantoor", alarmId = 1
        )
        assertTrue("Eerste CHANGED moet gemeld worden (dedup passeert)", r1)
        assertEquals(1, notificationCount())
        val r2 = AlarmNotificationHelper.notifyAlarmEvent(
            context, AlarmNotificationHelper.AlarmEvent.CHANGED, time = "07:30", label = "Kantoor", alarmId = 1
        )
        assertFalse("Identieke CHANGED (zelfde event|time|label) moet stil zijn (L9)", r2)
        assertEquals("Dedup-blokkade post niets nieuws", 1, notificationCount())

        // Andere content → dedup passeert wel (returnwaarde is de bewering; het
        // fysieke aantal differs per notificatie-ID en is hier niet relevant)
        val r3 = AlarmNotificationHelper.notifyAlarmEvent(
            context, AlarmNotificationHelper.AlarmEvent.CHANGED, time = "08:00", label = "Kantoor", alarmId = 2
        )
        assertTrue("CHANGED met andere tijd moet gemeld worden (dedup passeert)", r3)
    }
}
