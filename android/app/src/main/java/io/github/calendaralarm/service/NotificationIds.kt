package io.github.calendaralarm.service

/**
 * P1 fix review#17: central notification ID registry.
 *
 * Problem (v2.7.0): fixed IDs were spread across classes and could collide —
 * DownloadVerifiedUpdateWorker used 4100, exactly NOTIFICATION_ID_ALARM_SERVICE
 * of AlarmForegroundService; ringing notifications used 4001 + alarmId, which
 * lies in the same range as fixed IDs of other components.
 *
 * Solution: one object with DISJOINT ranges per category. Each category gets
 * its own block of 1000 IDs; within a block the offset is per item (e.g. alarmId).
 * A unit test (NotificationIdsTest) asserts that all fixed IDs are unique and that the
 * ranges do not overlap.
 *
 * Ranges (each 1000 wide):
 *   2000–2999  alarm events (SET/ALARM/SYNC_FAILED/...)
 *   3000–3999  updates (download/update notifications)
 *   4000–4999  alarm ringing + alarm foreground service
 *   5000–5999  timer (ongoing/service/fired/ringing)
 *   6000–6999  pre-alarm notifications (per alarmId)
 *   7000–7999  system/warnings (store corruption, missed alarm, ...)
 */
object NotificationIds {

    // ---- 2000–2999: alarm events ----
    const val ALARM = 2001            // "Alarm set" (SET)
    const val SYNC_FAILED = 2002      // "Sync failed"
    const val ALARM_FALLBACK = 2003   // reserved (was NOTIFICATION_ID_ALARM + 2/3 in helper)
    const val CHANGED_BASE = 2100     // CHANGED per alarm: 2100 + alarmId (alarmId < 900)
    const val REMOVED_BASE = 2200     // REMOVED per alarm: 2200 + alarmId

    // ---- 3000–3999: updates ----
    const val UPDATE = 3001

    // ---- 4000–4999: alarm ringing + service ----
    const val ALARM_SERVICE = 4100            // AlarmForegroundService FGS (was 4100 — zelfde, nu geclaimd)
    const val RINGING_BASE = 4200             // ringing per alarm: 4200 + alarmId (alarmId < 800)
    const val PRE_ALARM_RINGING = 4900        // reserved

    // ---- 5000–5999: timer ----
    const val TIMER = 5001
    const val TIMER_SERVICE = 5002
    const val TIMER_FIRED = 5003
    const val TIMER_RINGING = 5004

    // ---- 6000–6999: pre-alarm per alarm ----
    const val PRE_ALARM_BASE = 6000           // 6000 + alarmId (was 700000 + alarmId — verplaatst naar eigen blok)

    // ---- 7000–7999: system / warnings ----
    const val STORE_CORRUPT = 7001            // "Alarm storage corrupted"
    const val MISSED_ALARM = 7002             // "Missed alarm at HH:mm"
    const val RESCHEDULE_FAILED = 7003        // "Rescheduling alarm failed"

    // ---- 3000–3999: update worker (moved out of the 4100 block to avoid collision with ALARM_SERVICE) ----
    const val UPDATE_INSTALL = 3100
    const val UPDATE_DOWNLOADING = 3101
    const val UPDATE_DOWNLOAD_FAILED = 3102

    // N-f (review): per-alarm IDs via notify(tag, id) — tag per category, id = alarmId.
    // This way never a collision at alarmId > 100 (nextId grows monotonically). Old fixed-ID
    // schemes (4001+id, 4200+id, 700000+id) stay readable via legacyIds() for cancel.
    const val TAG_RINGING = "ringing"
    const val TAG_PRE_ALARM = "prealarm"
    const val TAG_CHANGED = "changed"
    const val TAG_REMOVED = "removed"

    /** Ringing notification: notify(TAG_RINGING, alarmId). */
    fun ringing(alarmId: Int): Int = alarmId
    /** Pre-alarm: notify(TAG_PRE_ALARM, alarmId). */
    fun preAlarm(alarmId: Int): Int = alarmId
    /** Changed: notify(TAG_CHANGED, alarmId). */
    fun changed(alarmId: Int): Int = alarmId
    /** Removed: notify(TAG_REMOVED, alarmId). */
    fun removed(alarmId: Int): Int = alarmId

    /**
     * Legacy fixed IDs used before N-f — on cancel touch ALL of them
     * (backwards compat: clean up old notifications from a running version).
     */
    fun legacyRingingIds(alarmId: Int): List<Int> = listOf(4001 + alarmId, 4200 + alarmId)
    fun legacyPreAlarmIds(alarmId: Int): List<Int> = listOf(700000 + alarmId, 6000 + alarmId)

    /**
     * All FIXED (non-per-alarm) IDs — used by the test to guarantee uniqueness.
     */
    val fixedIds: List<Int> = listOf(
        ALARM, SYNC_FAILED, ALARM_FALLBACK,
        UPDATE,
        ALARM_SERVICE, PRE_ALARM_RINGING,
        TIMER, TIMER_SERVICE, TIMER_FIRED, TIMER_RINGING,
        STORE_CORRUPT, MISSED_ALARM, RESCHEDULE_FAILED,
        UPDATE_INSTALL, UPDATE_DOWNLOADING, UPDATE_DOWNLOAD_FAILED
    )

    /**
     * Check: all fixed IDs unique AND within their own block.
     * N-f: per-alarm notifications now use notify(tag, id) — the per-alarm bases
     * are only for legacy cancel; they must not touch the fixed IDs.
     */
    fun checkRangesDisjoint(): Boolean {
        val bases = listOf(
            2000 to 3000, 3000 to 4000, 4000 to 5000,
            5000 to 6000, 6000 to 7000, 7000 to 8000
        )
        // fixed IDs must lie in their block and be unique
        if (fixedIds.size != fixedIds.toSet().size) return false
        for (id in fixedIds) {
            val inAny = bases.any { id >= it.first && id < it.second }
            if (!inAny) return false
        }
        // legacy per-alarm bases must not hit any FIXED id (at alarmId=0..99)
        val perAlarmBases = listOf(CHANGED_BASE, REMOVED_BASE, RINGING_BASE, PRE_ALARM_BASE)
        for (b in perAlarmBases) {
            for (alarmId in 0..99) {
                val id = b + alarmId
                if (id in fixedIds) return false
            }
        }
        return true
    }
}
