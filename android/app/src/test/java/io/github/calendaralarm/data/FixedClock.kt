package io.github.calendaralarm.data

import org.robolectric.shadows.ShadowPausedSystemClock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase 0 (safety net): deterministic clock for characterization tests.
 *
 * Robolectric 4.14 runs in PAUSED looper mode by default: System.currentTimeMillis()
 * delegates to ShadowPausedSystemClock.currentRealtimeMillis, its own static counter
 * (independent of the real JVM clock). `setCurrentTimeMillis(long)` is protected static — we set
 * it via reflection (the Robolectric sandbox allows setAccessible on shadows).
 */
object FixedClock {

    private val setCurrentTimeMillis by lazy {
        val m = ShadowPausedSystemClock::class.java.getDeclaredMethod("setCurrentTimeMillis", Long::class.javaPrimitiveType)
        m.isAccessible = true
        m
    }

    private var zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    /** Set the Robolectric clock to an absolute epoch moment and the default time zone. */
    fun set(zonedDateTime: ZonedDateTime) {
        zone = zonedDateTime.zone
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
        setMillis(zonedDateTime.toInstant().toEpochMilli())
    }

    fun set(epochMillis: Long, zoneId: String = "Europe/Amsterdam") {
        zone = ZoneId.of(zoneId)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
        setMillis(epochMillis)
    }

    fun set(localDateTime: String, zoneId: String = "Europe/Amsterdam") {
        val z = ZoneId.of(zoneId)
        val ldt = java.time.LocalDateTime.parse(localDateTime.replace(' ', 'T'))
        set(ZonedDateTime.of(ldt, z))
    }

    @Volatile private var fixedMillis: Long = 0L

    /**
     * Set AppClock (the clock source of the app code) to a fixed value. The Robolectric shadow
     * is also set best-effort, but it turned out NOT to affect System.currentTimeMillis() in app code —
     * AppClock is the only reliable route.
     */
    private fun setMillis(epochMillis: Long) {
        fixedMillis = epochMillis
        io.github.calendaralarm.data.AppClock.nowProvider = { fixedMillis }
        try { setCurrentTimeMillis.invoke(null, epochMillis) } catch (_: Throwable) {}
    }

    /** Move the fixed clock forward (simulate time passing). */
    fun advance(ms: Long) = setMillis(fixedMillis + ms)

    fun now(): Long = io.github.calendaralarm.data.AppClock.now()

    fun zone(): ZoneId = zone

    fun instantOf(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)

    /** Restore default time zone (cleaned up after the test). */
    fun reset() {
        io.github.calendaralarm.data.AppClock.reset()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }
}
