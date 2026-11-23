package io.github.calendaralarm.data

/**
 * Single clock source for all alarm/timer decisions.
 *
 * Production: System.currentTimeMillis(). Tests set [nowProvider] (via FixedClock) so that
 * time-dependent behavior (today/tomorrow, DST, missed, snooze) is tested deterministically.
 * Robolectric does NOT reliably shadow System.currentTimeMillis() — therefore never use
 * System.currentTimeMillis() or java.time.*.now() directly in alarm logic.
 */
object AppClock {
    @Volatile
    var nowProvider: () -> Long = { System.currentTimeMillis() }

    fun now(): Long = nowProvider()

    /** Back to the system clock (tests: afterwards). */
    fun reset() {
        nowProvider = { System.currentTimeMillis() }
    }
}
