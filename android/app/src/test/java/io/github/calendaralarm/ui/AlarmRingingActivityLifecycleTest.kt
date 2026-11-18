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
 * stopte de alarm-foreground-service AL bij rotatie/config-change/back — het alarm ging
 * stil zonder snooze/dismiss.
 *
 * Fix: onDestroy stopt de service ALLEEN als er geen expliciete gebruikersactie was
 * EN het géén config-change is. Back-gesture = snooze (of dismiss) via OnBackPressedCallback.
 *
 * Deze tests bewijzen met ActivityScenario.recreate() (simuleert rotatie/config-change)
 * dat de stop-service intent NIET wordt verstuurd. We meten via ShadowApplication's
 * `nextStoppedServiceIntent` (consumeerbaar) zodat we de stop-intents kunnen tellen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AlarmRingingActivityLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AlarmStore(context).removeAll()
    }

    private fun shadowApp(): ShadowApplication =
        shadowOf(ApplicationProvider.getApplicationContext<Application>())

    /** Aantal stop-intents voor AlarmForegroundService dat tot nu toe is verstuurd. */
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
        // Seed een alarm zodat de activity z'n state kan lezen
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
        drainStopServiceIntents() // wis historisch

        launchRinging(1).use { scenario ->
            // Simuleer rotatie / config-change → activity wordt gerecreate
            scenario.recreate()

            val stops = drainStopServiceIntents()
            assertEquals(
                "H2: recreate (config-change) mag de alarm-service NIET stoppen — het alarm moet blijven rinkelen",
                0, stops
            )
        }
    }

    @Test
    fun `H2 plain destroy without user action stops service as safety net`() {
        drainStopServiceIntents() // wis historisch

        val scenario = launchRinging(1)
        // Gewone destroy (geen recreate, geen actie, isChangingConfigurations=false) → vangnet
        scenario.close()

        val stops = drainStopServiceIntents()
        assertTrue(
            "H2: plain destroy zonder actie en zonder config-change moet de service als vangnet stoppen",
            stops >= 1
        )
    }
}
