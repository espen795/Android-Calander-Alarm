package io.github.calendaralarm.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1 fix review#8: unit tests for the pure decision logic of PowerButtonWatcher.
 *
 * Guard: a SCREEN_OFF only counts as a power button press if the app holds the screen
 * wakelock AND the screen was on for at least 1.5 s before. With the "turn off"
 * setting but < 10 s ringing → snooze (fail-safe: when in doubt NEVER turn off).
 */
class PowerButtonDecisionTest {

    @Test
    fun `screen off without wakelock is ignored (proximity sensor)`() {
        val o = PowerButtonDecision.shouldCountAsPowerButton(
            screenWakeLockHeld = false,
            screenWasOnForMs = 60_000,
            ringingForMs = 60_000,
            configuredAction = "dismiss"
        )
        assertEquals(PowerButtonDecision.Outcome.IGNORE, o)
    }

    @Test
    fun `screen off with wakelock but screen on under 1-5s is ignored`() {
        val o = PowerButtonDecision.shouldCountAsPowerButton(
            screenWakeLockHeld = true,
            screenWasOnForMs = 1_000,  // < 1500
            ringingForMs = 60_000,
            configuredAction = "dismiss"
        )
        assertEquals(PowerButtonDecision.Outcome.IGNORE, o)
    }

    @Test
    fun `screen off with wakelock and screen on at least 1-5s counts as button press`() {
        val o = PowerButtonDecision.shouldCountAsPowerButton(
            screenWakeLockHeld = true,
            screenWasOnForMs = 1_500,
            ringingForMs = 60_000,
            configuredAction = "dismiss"
        )
        assertEquals(PowerButtonDecision.Outcome.POWER_BUTTON, o)
    }

    @Test
    fun `with turn off but ringing under 10s becomes snooze instead of dismiss`() {
        val o = PowerButtonDecision.shouldCountAsPowerButton(
            screenWakeLockHeld = true,
            screenWasOnForMs = 5_000,
            ringingForMs = 5_000,  // < 10_000
            configuredAction = "dismiss"
        )
        assertEquals(PowerButtonDecision.Outcome.SNOOZE_INSTEAD_OF_DISMISS, o)
    }

    @Test
    fun `with turn off and ringing 10s or longer it is a real dismiss`() {
        val o = PowerButtonDecision.shouldCountAsPowerButton(
            screenWakeLockHeld = true,
            screenWasOnForMs = 5_000,
            ringingForMs = 10_000,  // boundary — no longer "too fast"
            configuredAction = "dismiss"
        )
        assertEquals(PowerButtonDecision.Outcome.POWER_BUTTON, o)
    }

    @Test
    fun `with snooze setting, ringing under 10s is just a button press (no override needed)`() {
        val o = PowerButtonDecision.shouldCountAsPowerButton(
            screenWakeLockHeld = true,
            screenWasOnForMs = 5_000,
            ringingForMs = 3_000,
            configuredAction = "snooze"
        )
        assertEquals(PowerButtonDecision.Outcome.POWER_BUTTON, o)
    }

    @Test
    fun `wait time 0 ringing (just started) with turn off → snooze`() {
        val o = PowerButtonDecision.shouldCountAsPowerButton(
            screenWakeLockHeld = true,
            screenWasOnForMs = 2_000,
            ringingForMs = 0,
            configuredAction = "dismiss"
        )
        assertEquals(PowerButtonDecision.Outcome.SNOOZE_INSTEAD_OF_DISMISS, o)
    }
}
