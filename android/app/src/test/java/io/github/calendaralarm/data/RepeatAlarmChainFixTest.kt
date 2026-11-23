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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v2.6.13: regression tests for the CRITICAL/HIGH repeat-chain fixes from
 * ARCHITECT-REVIEW-2026-11-12 §B2/§B3:
 *
 * - C1  (CRITICAL): after "Skip this time" the NEXT occurrence MUST re-arm.
 * - C1b (CRITICAL): snooze + dismiss must NOT wipe the repeat chain.
 * - H7  (HIGH): snooze of a repeat alarm must NOT give a duplicate re-arm.
 * - H3  (HIGH): a user-disabled smart alarm must NOT be re-enabled by sync.
 *
 * The receivers are called directly (BroadcastReceiver.onReceive) — Robolectric
 * suffices for AlarmManager/store; the foreground service start is a shadow no-op.
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
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
        // NB: @Config(sdk=[26]) → canScheduleExactAlarms() is always true on SDK<31,
        // so schedule() goes through the AlarmManager path without extra shadow setup.
        repo = AlarmRepository(context)
        store = repo.store
    }

    @After
    fun tearDown() {
        store.removeAll()
        context.getSharedPreferences("AlarmRepositoryState", Context.MODE_PRIVATE).edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "AlarmRepositoryState").edit().clear().commit()
    }

    /** Build a repeat alarm with a trigger in the near future. */
    private fun makeRepeatAlarm(
        id: Int = -1,  // -1 = fresh store.nextId() (prevents id collision with re-arm/snooze records)
        timeLabel: String = "07:00",
        repeatDays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
        skipCount: Int = 0,
        isSnooze: Boolean = false,
        enabled: Boolean = true,
        triggerAtMillis: Long = System.currentTimeMillis() + 3_600_000
    ): AlarmStore.OwnedAlarm {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(triggerAtMillis), zone).toString()
        val realId = if (id >= 0) id else store.nextId()
        return AlarmStore.OwnedAlarm(
            id = realId,
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

    @Test
    fun `C1 skip consumes skipCount so next occurrence re-arms after firing`() {
        val alarm = makeRepeatAlarm(skipCount = 0)
        store.save(alarm)
        val id = alarm.id

        // 1. User taps "Skip this time" in the pre-alarm
        skip(id)

        // 2. The record has shifted to the next occurrence and skipCount is consumed
        val afterSkip = store.get(id)
        assertNotNull(afterSkip)
        assertEquals("C1: skipCount must be consumed (0) after skip", 0, afterSkip!!.skipCount)
        assertTrue("C1: trigger must have shifted to the future", afterSkip.triggerAtMillis > System.currentTimeMillis())

        // 3. Fire the (shifted) alarm → a NEW re-armed occurrence must appear
        fireAlarm(id)

        val reArmed = store.getAll().firstOrNull { it.id != id && !it.isSnooze && it.enabled }
        assertNotNull("C1: after skip + fire a next occurrence must be re-armed", reArmed)
        assertEquals("C1: re-armed occurrence must have skipCount=0", 0, reArmed!!.skipCount)
        assertTrue("C1: re-armed occurrence must be in the future", reArmed.triggerAtMillis > System.currentTimeMillis())
    }

    @Test
    fun `C1 rescheduleAll re-arms repeat alarm even if skipCount was left non-zero`() {
        // Simulate an abnormal record (skipCount > 0) with a trigger in the past
        val pastTrigger = System.currentTimeMillis() - 3_600_000
        val alarm = makeRepeatAlarm(skipCount = 1, triggerAtMillis = pastTrigger)
        store.save(alarm)

        // Boot → rescheduleAll (before the fix: guard skipCount==0 deleted the record)
        repo.rescheduleAll()

        val after = store.get(alarm.id)
        assertNotNull("C1: rescheduleAll must NOT delete a repeat alarm with skipCount>0", after)
        assertTrue("C1: rescheduleAll must shift the alarm to the future", after!!.triggerAtMillis > System.currentTimeMillis())
        assertEquals("C1: rescheduleAll resets skipCount to 0", 0, after.skipCount)
        assertTrue(after.enabled)
    }

    // ============== C1b: snooze + dismiss does NOT wipe the repeat chain ==============

    @Test
    fun `C1b snooze then dismiss keeps repeat chain intact (re-armed occurrence survives)`() {
        val alarm = makeRepeatAlarm(skipCount = 0)
        store.save(alarm)
        val id = alarm.id

        // 1. Original alarm fires → re-arm to next week (N1, orig=nextTrigger)
        fireAlarm(id)
        val reArmed = store.getAll().firstOrNull { it.id != id && !it.isSnooze && it.enabled }
        assertNotNull("C1b: re-arm after fire expected", reArmed)
        val reArmedId = reArmed!!.id
        // v2.6.13 C1b: originalTriggerMillis of the re-arm = nextTrigger (NOT the old T0)
        assertEquals("C1b: re-arm originalTriggerMillis = nextTrigger", reArmed.triggerAtMillis, reArmed.originalTriggerMillis)

        // 2. Snooze the original (now disabled) alarm → snooze record S (isSnooze=true)
        snooze(id)
        val snoozeRec = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull("C1b: snooze record expected", snoozeRec)
        val snoozeId = snoozeRec!!.id

        // 3. Dismiss the snooze → only the snooze cascade is cancelled, NOT the re-arm
        dismiss(snoozeId)

        val stillThere = store.get(reArmedId)
        assertNotNull("C1b: the re-armed occurrence (next week) must SURVIVE the dismiss of the snooze", stillThere)
        assertTrue("C1b: re-armed occurrence must still be enabled", stillThere!!.enabled)
        assertNull("C1b: the snooze record itself is deleted", store.get(snoozeId))
    }

    // ============== H7: snooze of a repeat alarm does NOT duplicate ==============

    @Test
    fun `H7 snooze of repeat alarm does not create duplicate re-arm`() {
        val alarm = makeRepeatAlarm(skipCount = 0)
        store.save(alarm)
        val id = alarm.id

        // 1. Fire → re-arm N1 (1 extra record)
        fireAlarm(id)
        val countAfterFire = store.getAll().count { !it.isSnooze && it.enabled }
        assertEquals("H7: na fire precies 1 re-armed occurrence", 1, countAfterFire)

        // 2. Snooze the original alarm → snooze record S
        snooze(id)
        val snoozeRec = store.getAll().firstOrNull { it.isSnooze }
        assertNotNull(snoozeRec)

        // 3. Fire the SNOOZE (isSnooze=true) → v2.6.13 H7: NO extra re-arm
        fireAlarm(snoozeRec!!.id)

        val reArmCount = store.getAll().count { !it.isSnooze && it.enabled && it.id != id }
        assertEquals("H7: snooze fire must NOT produce a duplicate re-arm (re-arm was already done at the original)", 1, reArmCount)
    }

    // ============== H3: user-disabled smart alarm not re-enabled by sync ==============

    @Test
    fun `H3 user-disabled smart alarm is not re-enabled by replaceForDate`() {
        val date = LocalDate.now().plusDays(1)
        val zone = ZoneId.systemDefault()
        val trigger = ZonedDateTime.of(date, java.time.LocalTime.of(7, 0), zone).toInstant().toEpochMilli()
        val id = store.nextId()
        val alarm = AlarmStore.OwnedAlarm(
            id = id,
            triggerAtMillis = trigger,
            timeLabel = "07:00",
            label = "Kantoor",
            date = date.toString(),
            type = "office",
            enabled = false,
            userDisabled = true  // user disabled this via the UI toggle
        )
        store.save(alarm)

        // Sync pushes a plan for the same date
        val result = repo.replaceForDate(
            date = date,
            timeLabel = "07:00",
            label = "Kantoor",
            type = "office",
            silent = true
        )

        // v2.6.13 H3: the existing user-disabled alarm is returned, NOT replaced
        assertNotNull(result)
        assertEquals("H3: sync must return the existing (user-disabled) alarm", id, result!!.id)
        val after = store.get(id)
        assertNotNull(after)
        assertFalse("H3: user-disabled alarm must stay DISABLED after sync", after!!.enabled)
        assertTrue("H3: userDisabled flag must be preserved", after.userDisabled)
        // No new enabled alarm may have been created for this date
        val enabledForDate = store.getAll().filter { it.date == date.toString() && it.enabled && !it.isSnooze }
        assertEquals("H3: no enabled alarm for this date after sync", 0, enabledForDate.size)
    }

    @Test
    fun `H3 setEnabled userAction=false does not set userDisabled flag`() {
        val alarm = makeRepeatAlarm(enabled = true)
        store.save(alarm)
        val id = alarm.id

        // auto-disable (userAction=false) — e.g. internal/system path: no userDisabled
        repo.setEnabled(id, false, userAction = false)
        val after = store.get(id)
        assertNotNull(after)
        assertFalse(after!!.enabled)
        assertFalse("H3: auto-disable must NOT set userDisabled", after.userDisabled)

        // user-disable (userAction=true) — UI toggle: userDisabled set
        repo.setEnabled(id, true, userAction = true)
        repo.setEnabled(id, false, userAction = true)
        val afterUser = store.get(id)
        assertNotNull(afterUser)
        assertFalse(afterUser!!.enabled)
        assertTrue("H3: user-disable must set userDisabled", afterUser.userDisabled)
    }

    @Test
    fun `H3 replaceForDate re-enables auto-disabled (non-user) alarm normally`() {
        val date = LocalDate.now().plusDays(1)
        val zone = ZoneId.systemDefault()
        val trigger = ZonedDateTime.of(date, java.time.LocalTime.of(7, 0), zone).toInstant().toEpochMilli()
        val id = store.nextId()
        val alarm = AlarmStore.OwnedAlarm(
            id = id,
            triggerAtMillis = trigger,
            timeLabel = "07:00",
            label = "Kantoor",
            date = date.toString(),
            type = "office",
            enabled = false,
            userDisabled = false  // auto-disable (e.g. ringing state) — sync may re-enable
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
        // auto-disabled (not user) → sync re-enables normally
        val enabledCount = store.getAll().count { it.date == date.toString() && it.enabled && !it.isSnooze }
        assertTrue("H3: auto-disabled alarm must be re-enabled by sync", enabledCount >= 1)
    }
}
