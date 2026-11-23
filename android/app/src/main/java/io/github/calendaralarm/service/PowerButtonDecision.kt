package io.github.calendaralarm.service

/**
 * P1 fix review#8: pure decision logic for PowerButtonWatcher — WHEN does a
 * SCREEN_OFF count as a real power-button press?
 *
 * Problem (v2.7.0): every SCREEN_OFF after the grace period triggered the button action.
 * A SCREEN_OFF that did NOT come from the power button (proximity sensor, system
 * ignoring the screen wakelock, timeout despite wakelock) then turned the alarm
 * off incorrectly.
 *
 * Decision (fail-safe towards RINGING, never turn off when in doubt):
 * - [screenWakeLockHeld] must be true: only if the app actually holds the screen
 *   wakelock can the screen only be turned off via the power button.
 *   NOTE (N-g): this guard does NOT protect against proximity — the proximity sensor
 *   can also turn the screen off WITH a wakelock. The real protection is the 10 s rule.
 * - [screenWasOnForMs] ≥ [MIN_SCREEN_ON_MS]: the screen must have been on for at least
 *   1.5 s — shorter is suspicious (system/transition artifact). The watcher
 *   updates screenOnAt on every ACTION_SCREEN_ON (N-g), which keeps this guard
 *   meaningful after a proximity cycle.
 * - If the setting is "turn off" BUT the alarm has not been ringing for 10 s yet
 *   ([ringingForMs] < [EARLY_SNOOZE_MS]) → treat as SNOOZE (too fast to be
 *   deliberate — the user did not intentionally press the button). This is
 *   the primary protection against a pocket/hand press.
 *
 * The watcher itself implements this via [shouldCountAsPowerButton]; the pure
 * function here is unit-testable without Android.
 */
object PowerButtonDecision {

    /** Minimum time the screen must have been on to attribute a SCREEN_OFF
     *  to a button press (proximity/transition guard). */
    const val MIN_SCREEN_ON_MS = 1_500L

    /** If the alarm has been ringing for less than this, a SCREEN_OFF is suspiciously fast —
     *  with the "turn off" setting we treat it as SNOOZE. */
    const val EARLY_SNOOZE_MS = 10_000L

    enum class Outcome { IGNORE, POWER_BUTTON, SNOOZE_INSTEAD_OF_DISMISS }

    /**
     * @param screenWakeLockHeld does the app hold the screen wakelock? (only then
     *   can SCREEN_OFF come only from the power button)
     * @param screenWasOnForMs how long was the screen on before this SCREEN_OFF
     * @param ringingForMs how long has the alarm been ringing (0 if no active alarm)
     * @param configuredAction "dismiss" or "snooze" (hardware button setting)
     */
    fun shouldCountAsPowerButton(
        screenWakeLockHeld: Boolean,
        screenWasOnForMs: Long,
        ringingForMs: Long,
        configuredAction: String  // "dismiss" | "snooze"
    ): Outcome {
        // Guard 1: without a held screen wakelock, SCREEN_OFF cannot reliably be
        // attributed to the button (proximity/system can turn the screen off).
        if (!screenWakeLockHeld) return Outcome.IGNORE
        // Guard 2: the screen must have been on for at least 1.5 s
        if (screenWasOnForMs < MIN_SCREEN_ON_MS) return Outcome.IGNORE
        // Guard 3: with "turn off" but < 10 s ringing → snooze (too fast to be deliberate)
        if (configuredAction == "dismiss" && ringingForMs in 0 until EARLY_SNOOZE_MS) {
            return Outcome.SNOOZE_INSTEAD_OF_DISMISS
        }
        return Outcome.POWER_BUTTON
    }
}
