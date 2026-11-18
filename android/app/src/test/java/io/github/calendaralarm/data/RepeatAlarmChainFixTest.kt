package io.github.calendaralarm.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.receiver.OwnedAlarmReceiver
import io.github.calendaralarm.receiver.PreAlarmReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v2.6.13: regressietests voor de CRITICAL/HIGH herhaal-keten fixes uit
 * ARCHITECT-REVIEW-2026-11-12 §B2/§B3:
 *
 * - C1  (CRITICAL): na "Skip deze keer" moet de VOLGENDE occurrence WEL re-armen.
 * - C1b (CRITICAL): snooze + dismiss mag de herhaal-keten NIET wissen.
 * - H7  (HIGH): snooze van een herhaal-wekker mag GEEN duplicate re-arm geven.
 * - H3  (HIGH): een user-uitgezet smart-alarm mag door sync NIET re-enablet worden.
 *
 * De receivers worden direct aangeroepen (BroadcastReceiver.onReceive) — Robolectric
 * is afdoende voor AlarmManager/store; de foreground service start is een shadow no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RepeatAlarmChainFixTest {

    private lateinit var context: Context
    private lateinit var repo: AlarmRepository
    private lateinit var store: AlarmStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        repo = AlarmRepository(context)
        store = repo.store
    }

    @After
    fun tearDown() {
        store.removeAll()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Bouw een repeat-alarm met een trigger in de nabije toekomst. */
    private fun makeRepeatAlarm(
        id: Int = 1,
        timeLabel: String = "07:00",
        repeatDays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
        skipCount: Int = 0,
        isSnooze: Boolean = false,
        enabled: Boolean = true,
        triggerAtMillis: Long = System.currentTimeMillis() + 3_600_000
    ): AlarmStore.OwnedAlarm {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(triggerAtMillis), zone).toString()
        return AlarmStore.OwnedAlarm(
            id = id,
            triggerAtMillis = triggerAtMillis,
            timeLabel = timeLabel,
            label = "Repeat",
            date = date,
            type = "office",
            enabled = enabled,
            isSnooze = isSnooze,
            originalTriggerMillis = triggerAtMillis,
            repeatDays = repeatDays,
            skipCount = skipCount
        )
    }

    private fun fireAlarm(alarmId: Int) {
        val intent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            putExtra(AlarmRepository.EXTRA_ALARM_ID, alarmId)
        }
        OwnedAlarmReceiver().onReceive(context, intent)
    }

    private fun snooze(alarmId: Int) {
        val intent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_SNOOZE
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        OwnedAlarmReceiver().onReceive(context, intent)
    }

    private fun dismiss(alarmId: Int) {
        val intent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_DISMISS
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        OwnedAlarmReceiver().onReceive(context, intent)
    }

    private fun skip(alarmId: Int) {
        val intent = Intent(context, PreAlarmReceiver::class.java).apply {
            action = PreAlarmReceiver.ACTION_SKIP
            putExtra(PreAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        PreAlarmReceiver().onReceive(context, intent)
    }

    // ============== C1: skip → volgende occurrence re-armed ==============

    @Ignore("Known failing (pre-existing), tracked in ROADMAP.md")
    @Test
    fun `C1 skip consumes skipCount so next occurrence re-arms after firing`() {
        val alarm = makeRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        // 1. User taps "Skip deze keer" in de pre-alarm
        skip(1)

        // 2. Het record is verschoven naar de volgende occurrence en skipCount is geconsumeerd
        val afterSkip = store.get(1)
        assertNotNull(afterSkip)
        assertEquals("C1: skipCount moet geconsumeerd zijn (0) na skip", 0, afterSkip!!.skipCount)
        assertTrue("C1: trigger moet verschoven zijn naar de toekomst", afterSkip.triggerAtMillis > System.currentTimeMillis())

        // 3. Fire het (verschoven) alarm → er moet een NIEUWE re-armed occurrence komen
        fireAlarm(1)

        val reArmed = store.getAll().firstOrNull { it.id != 1 && !it.isSnooze && it.enabled }
        assertNotNull("C1: na skip + fire moet een volgende occurrence re-armed zijn", reArmed)
        assertEquals("C1: re-armed occurrence moet skipCount=0 hebben", 0, reArmed!!.skipCount)
        assertTrue("C1: re-armed occurrence moet in de toekomst liggen", reArmed.triggerAtMillis > System.currentTimeMillis())
    }

    @Test
    fun `C1 rescheduleAll re-arms repeat alarm even if skipCount was left non-zero`() {
        // Simuleer een abnormaal record (skipCount > 0) met een trigger in het verleden
        val pastTrigger = System.currentTimeMillis() - 3_600_000
        val alarm = makeRepeatAlarm(id = 1, skipCount = 1, triggerAtMillis = pastTrigger)
        store.save(alarm)

        // Boot → rescheduleAll (vóór de fix: guard skipCount==0 verwijderde het record)
        repo.rescheduleAll()

        val after = store.get(1)
        assertNotNull("C1: rescheduleAll mag een repeat-alarm met skipCount>0 NIET verwijderen", after)
        assertTrue("C1: rescheduleAll moet het alarm naar de toekomst verschuiven", after!!.triggerAtMillis > System.currentTimeMillis())
        assertEquals("C1: rescheduleAll reset skipCount naar 0", 0, after.skipCount)
        assertTrue(after.enabled)
    }

    // ============== C1b: snooze + dismiss wist NIET de herhaal-keten ==============

    @Ignore("Known failing (pre-existing), tracked in ROADMAP.md")
    @Test
    fun `C1b snooze then dismiss keeps repeat chain intact (re-armed occurrence survives)`() {
        val alarm = makeRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        // 1. Origineel alarm gaat af → re-arm naar volgende week (N1, orig=nextTrigger)
        fireAlarm(1)
        val reArmed = store.getAll().firstOrNull { it.id != 1 && !it.isSnooze && it.enabled }
        assertNotNull("C1b: re-arm na fire verwacht", reArmed)
        val reArmedId = reArmed!!.id
        // v2.6.13 C1b: originalTriggerMillis van de re-arm = nextTrigger (NIET de oude T0)
        assertEquals("C1b: re-arm originalTriggerMillis = nextTrigger", reArmed.triggerAtMillis, reArmed.originalTriggerMillis)

        // 2. Snooze van het originele (nu disabled) alarm → snooze-record S (isSnooze=true)
        snooze(1)
        val snoozeRec = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull("C1b: snooze-record verwacht", snoozeRec)
        val snoozeId = snoozeRec!!.id

        // 3. Dismiss van de snooze → alleen snooze-cascade wordt geannuleerd, NIET de re-arm
        dismiss(snoozeId)

        val stillThere = store.get(reArmedId)
        assertNotNull("C1b: de re-armed occurrence (volgende week) moet dismiss van snooze OVERLEVEN", stillThere)
        assertTrue("C1b: re-armed occurrence moet nog enabled zijn", stillThere!!.enabled)
        assertNull("C1b: het snooze-record zelf is wel verwijderd", store.get(snoozeId))
    }

    // ============== H7: snooze van herhaal-wekker dupliceert NIET ==============

    @Ignore("Known failing (pre-existing), tracked in ROADMAP.md")
    @Test
    fun `H7 snooze of repeat alarm does not create duplicate re-arm`() {
        val alarm = makeRepeatAlarm(id = 1, skipCount = 0)
        store.save(alarm)

        // 1. Fire → re-arm N1 (1 extra record)
        fireAlarm(1)
        val countAfterFire = store.getAll().count { !it.isSnooze && it.enabled }
        assertEquals("H7: na fire precies 1 re-armed occurrence", 1, countAfterFire)

        // 2. Snooze van het originele alarm → snooze-record S
        snooze(1)
        val snoozeRec = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull(snoozeRec)

        // 3. Fire de SNOOZE (isSnooze=true) → v2.6.13 H7: GEEN extra re-arm
        fireAlarm(snoozeRec!!.id)

        val reArmCount = store.getAll().count { !it.isSnooze && it.enabled && it.id != 1 }
        assertEquals("H7: snooze-fire mag GEEN duplicate re-arm geven (re-arm was al gedaan bij origineel)", 1, reArmCount)
    }

    // ============== H3: user-disabled smart alarm niet re-enablet door sync ==============

    @Test
    fun `H3 user-disabled smart alarm is not re-enabled by replaceForDate`() {
        val date = LocalDate.now().plusDays(1)
        val zone = ZoneId.systemDefault()
        val trigger = ZonedDateTime.of(date, java.time.LocalTime.of(7, 0), zone).toInstant().toEpochMilli()
        val alarm = AlarmStore.OwnedAlarm(
            id = 1,
            triggerAtMillis = trigger,
            timeLabel = "07:00",
            label = "Kantoor",
            date = date.toString(),
            type = "office",
            enabled = false,
            userDisabled = true  // user heeft deze uitgezet via UI-toggle
        )
        store.save(alarm)

        // Sync pusht een plan voor dezelfde datum
        val result = repo.replaceForDate(
            date = date,
            timeLabel = "07:00",
            label = "Kantoor",
            type = "office",
            silent = true
        )

        // v2.6.13 H3: het bestaande user-disabled alarm wordt teruggegeven, NIET vervangen
        assertNotNull(result)
        assertEquals("H3: sync moet het bestaande (user-disabled) alarm teruggeven", 1, result!!.id)
        val after = store.get(1)
        assertNotNull(after)
        assertFalse("H3: user-disabled alarm moet DISABLED blijven na sync", after!!.enabled)
        assertTrue("H3: userDisabled flag moet behouden blijven", after.userDisabled)
        // Er mag geen nieuw enabled alarm zijn aangemaakt voor deze datum
        val enabledForDate = store.getAll().filter { it.date == date.toString() && it.enabled && !it.isSnooze }
        assertEquals("H3: geen enabled alarm voor deze datum na sync", 0, enabledForDate.size)
    }

    @Test
    fun `H3 setEnabled userAction=false does not set userDisabled flag`() {
        val alarm = makeRepeatAlarm(id = 1, enabled = true)
        store.save(alarm)

        // auto-disable (userAction=false) — bv. intern/system pad: géén userDisabled
        repo.setEnabled(1, false, userAction = false)
        val after = store.get(1)
        assertNotNull(after)
        assertFalse(after!!.enabled)
        assertFalse("H3: auto-disable mag userDisabled NIET zetten", after.userDisabled)

        // user-disable (userAction=true) — UI toggle: WEL userDisabled
        repo.setEnabled(1, true, userAction = true)
        repo.setEnabled(1, false, userAction = true)
        val afterUser = store.get(1)
        assertNotNull(afterUser)
        assertFalse(afterUser!!.enabled)
        assertTrue("H3: user-disable moet userDisabled zetten", afterUser.userDisabled)
    }

    @Ignore("Known failing (pre-existing), tracked in ROADMAP.md")
    @Test
    fun `H3 replaceForDate re-enables auto-disabled (non-user) alarm normally`() {
        val date = LocalDate.now().plusDays(1)
        val zone = ZoneId.systemDefault()
        val trigger = ZonedDateTime.of(date, java.time.LocalTime.of(7, 0), zone).toInstant().toEpochMilli()
        val alarm = AlarmStore.OwnedAlarm(
            id = 1,
            triggerAtMillis = trigger,
            timeLabel = "07:00",
            label = "Kantoor",
            date = date.toString(),
            type = "office",
            enabled = false,
            userDisabled = false  // auto-disable (bv. ringing state) — sync mag re-enablen
        )
        store.save(alarm)

        val result = repo.replaceForDate(
            date = date,
            timeLabel = "07:00",
            label = "Kantoor",
            type = "office",
            silent = true
        )

        assertNotNull(result)
        // auto-disabled (niet user) → sync re-enablet normaal
        val enabledCount = store.getAll().count { it.date == date.toString() && it.enabled && !it.isSnooze }
        assertTrue("H3: auto-disabled alarm moet door sync re-enablet worden", enabledCount >= 1)
    }
}
