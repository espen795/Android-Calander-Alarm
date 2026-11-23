package io.github.calendaralarm.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.service.AlarmForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * v2.6.13 H2 (HIGH, ARCHITECT-REVIEW-2026-11-12 §B2): `AlarmRingingActivity.onDestroy()`
 * stopped the alarm foreground service on EVERY rotation/config-change/back — the alarm
 * went silent without snooze/dismiss.
 *
 * Fix: onDestroy stops the service ONLY if there was no explicit user action
 * AND it is not a config-change. Back gesture = snooze (or dismiss) via OnBackPressedCallback.
 *
 * These tests prove with ActivityScenario.recreate() (simulates rotation/config-change)
 * that the stop-service intent is NOT sent. We measure via ShadowApplication's
 * `nextStoppedServiceIntent` (consumable) so we can count the stop intents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmRingingActivityLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
        io.github.calendaralarm.data.DeviceProtectedStorage.prefs(context, "calendar_alarm_store").edit().clear().commit()
    }

    @After
    fun tearDown() {
        AlarmStore(context).removeAll()
    }

    private fun shadowApp(): ShadowApplication =
        shadowOf(ApplicationProvider.getApplicationContext<Application>())

    /** Number of stop intents for AlarmForegroundService sent so far. */
    private fun drainStopServiceIntents(): Int {
        val shadow = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        var count = 0
        while (true) {
            val intent = shadow.nextStoppedService ?: break
            if (intent.component?.className == AlarmForegroundService::class.java.name) count++
        }
        return count
    }

    private fun launchRinging(alarmId: Int): ActivityScenario<AlarmRingingActivity> {
        // Seed an alarm so the activity can read its state
        AlarmStore(context).save(
            AlarmStore.OwnedAlarm(
                id = alarmId,
                triggerAtMillis = System.currentTimeMillis() + 3_600_000,
                timeLabel = "07:00",
                label = "Test",
                date = "2026-11-13",
                type = "office"
            )
        )
        val intent = Intent(context, AlarmRingingActivity::class.java).apply {
            putExtra(AlarmRepository.EXTRA_ALARM_ID, alarmId)
        }
        return ActivityScenario.launch(intent)
    }

    @Test
    fun `H2 recreate (rotatie config-change) does NOT stop the alarm service`() {
        drainStopServiceIntents() // clear history

        launchRinging(1).use { scenario ->
            // Simulate rotation / config-change → activity gets recreated
            scenario.recreate()

            val stops = drainStopServiceIntents()
            assertEquals(
                "H2: recreate (config-change) must NOT stop the alarm service — the alarm must keep ringing",
                0, stops
            )
        }
    }

    @Test
    fun `H2 plain destroy without user action stops service as safety net`() {
        drainStopServiceIntents() // wis historisch

        val scenario = launchRinging(1)
        // Plain destroy (no recreate, no action, isChangingConfigurations=false) → safety net
        scenario.close()

        val stops = drainStopServiceIntents()
        assertTrue(
            "H2: plain destroy without action and without config-change must stop the service as safety net",
            stops >= 1
        )
    }
}
