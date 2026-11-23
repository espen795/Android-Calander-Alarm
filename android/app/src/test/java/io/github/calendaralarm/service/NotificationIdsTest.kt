package io.github.calendaralarm.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 fix review#17: test that the notification ID registry has disjoint ranges
 * and that all fixed IDs are unique — the 4100 collision (DownloadVerifiedUpdateWorker
 * vs NOTIFICATION_ID_ALARM_SERVICE) must NEVER happen again.
 */
class NotificationIdsTest {

    @Test
    fun `all fixed notification IDs are unique`() {
        val ids = NotificationIds.fixedIds
        assertTrue("fixed IDs must not occur twice: $ids", ids.size == ids.toSet().size)
    }

    @Test
    fun `ranges are disjoint and per-alarm bases do not overlap`() {
        assertTrue("checkRangesDisjoint must be true", NotificationIds.checkRangesDisjoint())
    }

    @Test
    fun `update-worker IDs are in the 3000 block and not in the alarm-service block`() {
        // The historical bug: update install notification = 4100 = ALARM_SERVICE.
        assertTrue(NotificationIds.UPDATE_INSTALL < 4000)
        assertTrue(NotificationIds.UPDATE_DOWNLOADING < 4000)
        assertTrue(NotificationIds.UPDATE_DOWNLOAD_FAILED < 4000)
        assertEquals(4100, NotificationIds.ALARM_SERVICE)
        assertTrue(NotificationIds.ALARM_SERVICE >= 4000 && NotificationIds.ALARM_SERVICE < 5000)
    }

    @Test
    fun `n-f per-alarm ringing uses tag+id with id=alarmId (no block needed anymore)`() {
        // ringing(12345) = 12345 — no base+id, so no collision at high alarmIds
        assertEquals(12345, NotificationIds.ringing(12345))
        assertEquals(12345, NotificationIds.preAlarm(12345))
        assertEquals("ringing", NotificationIds.TAG_RINGING)
        assertEquals("prealarm", NotificationIds.TAG_PRE_ALARM)
    }

    @Test
    fun `n-f legacy IDs cover old schemes for backwards cancel`() {
        assertTrue(NotificationIds.legacyRingingIds(7).contains(4001 + 7))
        assertTrue(NotificationIds.legacyRingingIds(7).contains(4200 + 7))
        assertTrue(NotificationIds.legacyPreAlarmIds(7).contains(700000 + 7))
        assertTrue(NotificationIds.legacyPreAlarmIds(7).contains(6000 + 7))
    }

    @Test
    fun `legacy per-alarm bases touch no fixed IDs except the historical 4100 collision`() {
        // legacy ringing 4001+id / 4200+id and prealarm 6000+id / 700000+id must not
        // coincide with fixed IDs — EXCEPT the historical 4001+99=4100 vs
        // ALARM_SERVICE: exactly the bug that N-f solves (now tag+id, and 4100 is the
        // FGS notification that never collides with a ringing notification because ringing
        // now uses a tag).
        val protected = NotificationIds.fixedIds - NotificationIds.ALARM_SERVICE
        for (alarmId in 0..99) {
            for (id in NotificationIds.legacyRingingIds(alarmId) + NotificationIds.legacyPreAlarmIds(alarmId)) {
                assertTrue("legacy id $id must not be in fixed IDs $protected", id !in protected)
            }
        }
        // the historical collision is intentional: 4001+99 = 4100 = ALARM_SERVICE — that is
        // why ringing NOW uses tag+id (see test above)
        assertEquals(NotificationIds.ALARM_SERVICE, 4001 + 99)
    }
}
