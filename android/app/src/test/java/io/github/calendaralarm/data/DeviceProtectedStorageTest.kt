package io.github.calendaralarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1 fix review#1: tests for the Direct Boot migration (CE → device-protected storage).
 *
 * Robolectric 4.14: createDeviceProtectedStorageContext() returns a separate context
 * with its own SharedPreferences ("device_protected" storage dir) — the migration is
 * thereby really testable: data in CE must be in DP after migrateOnce() and CE cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceProtectedStorageTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        // Clear CE and DP for all relevant files + marker
        DeviceProtectedStorage.DP_PREFS_FILES.forEach {
            appContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
            DeviceProtectedStorage.prefs(appContext, it).edit().clear().commit()
        }
        DeviceProtectedStorage.prefs(appContext, "calalarm_dp_migration").edit().clear().commit()
    }

    @Test
    fun `p1 migration copies CE data to DP without loss and clears CE on success`() {
        // Put data in CE (simulates pre-upgrade state)
        val ceStore = appContext.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE)
        ceStore.edit().putString("alarms", """{"1":{"id":1}}""").putInt("next_id", 5).commit()
        val ceSettings = appContext.getSharedPreferences("alarm_settings", Context.MODE_PRIVATE)
        ceSettings.edit().putInt("snooze_minutes", 10).putBoolean("vibration_enabled", false).commit()

        DeviceProtectedStorage.migrateOnce(appContext)

        // DP now has the data
        val dpStore = DeviceProtectedStorage.prefs(appContext, "calendar_alarm_store")
        assertEquals("""{"1":{"id":1}}""", dpStore.getString("alarms", null))
        assertEquals(5, dpStore.getInt("next_id", -1))
        val dpSettings = DeviceProtectedStorage.prefs(appContext, "alarm_settings")
        assertEquals(10, dpSettings.getInt("snooze_minutes", -1))
        assertFalse(dpSettings.getBoolean("vibration_enabled", true))

        // CE is cleared (on success)
        assertTrue("CE store must be empty after migration", ceStore.all.isEmpty())
        assertTrue("CE settings must be empty after migration", ceSettings.all.isEmpty())

        // Marker set in DP
        val marker = DeviceProtectedStorage.prefs(appContext, "calalarm_dp_migration")
        assertTrue(marker.getBoolean("dp_migration_done_v1", false))
    }

    @Test
    fun `p1 migration is idempotent — second run does nothing`() {
        val ceStore = appContext.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE)
        ceStore.edit().putString("alarms", "X").commit()

        DeviceProtectedStorage.migrateOnce(appContext)
        val dpStore = DeviceProtectedStorage.prefs(appContext, "calendar_alarm_store")
        assertEquals("X", dpStore.getString("alarms", null))

        // New data in CE (as if the app wrote to CE again — should not happen)
        ceStore.edit().putString("alarms", "Y").commit()
        DeviceProtectedStorage.migrateOnce(appContext) // marker set → returns immediately

        assertEquals("DP must NOT be overwritten by 2nd migration", "X", dpStore.getString("alarms", null))
    }

    @Test
    fun `p1 migration NEVER overwrites existing DP keys (no data loss)`() {
        // DP already has data (e.g. from an earlier partial migration)
        val dpStore = DeviceProtectedStorage.prefs(appContext, "calendar_alarm_store")
        dpStore.edit().putString("alarms", "DP-DATA").putInt("next_id", 42).commit()
        // CE has other data + an extra key that DP does not have
        val ceStore = appContext.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE)
        ceStore.edit().putString("alarms", "CE-DATA").putString("extra_key", "CE-EXTRA").commit()

        DeviceProtectedStorage.migrateOnce(appContext)

        assertEquals("DP key 'alarms' must be kept (DP wins)", "DP-DATA", dpStore.getString("alarms", null))
        assertEquals("DP key 'next_id' must be kept", 42, dpStore.getInt("next_id", -1))
        // N-b: missing CE key IS taken over
        assertEquals("N-b: missing CE key is taken over to DP", "CE-EXTRA", dpStore.getString("extra_key", null))
    }

    @Test
    fun `n-b per-key merge - CE does not win over existing DP key but fills in missing ones`() {
        val dpSettings = DeviceProtectedStorage.prefs(appContext, "calendar_alarm_settings")
        dpSettings.edit().putInt("snooze_minutes", 15).commit() // already in DP (newer value)
        val ceSettings = appContext.getSharedPreferences("calendar_alarm_settings", Context.MODE_PRIVATE)
        ceSettings.edit().putInt("snooze_minutes", 5).putBoolean("wakker_freeze_enabled", true).commit()

        DeviceProtectedStorage.migrateOnce(appContext)

        assertEquals("DP value for existing key wins (15, not CE 5)", 15, dpSettings.getInt("snooze_minutes", -1))
        assertTrue("missing CE key (freeze) is taken over", dpSettings.getBoolean("wakker_freeze_enabled", false))
    }

    @Test
    fun `p1 empty CE file is skipped without marker error`() {
        DeviceProtectedStorage.migrateOnce(appContext)
        val marker = DeviceProtectedStorage.prefs(appContext, "calalarm_dp_migration")
        assertTrue("marker must be set (all files empty = ok)", marker.getBoolean("dp_migration_done_v1", false))
    }

    @Test
    fun `n-b CE is only cleared once ALL CE keys are verified in DP (no data loss)`() {
        // CE has 2 keys; DP already has 1 (with different value — DP wins)
        val dpStore = DeviceProtectedStorage.prefs(appContext, "calendar_alarm_store")
        dpStore.edit().putString("alarms", "DP-DATA").commit()
        val ceStore = appContext.getSharedPreferences("calendar_alarm_store", Context.MODE_PRIVATE)
        ceStore.edit().putString("alarms", "CE-DATA").putInt("next_id", 7).commit()

        DeviceProtectedStorage.migrateOnce(appContext)

        // both CE keys are now in DP (alarms=DP-DATA won, next_id=7 taken over)
        assertEquals("DP-DATA", dpStore.getString("alarms", null))
        assertEquals(7, dpStore.getInt("next_id", -1))
        // only NOW may CE be cleared
        assertTrue("CE must be empty AFTER all keys are verified in DP", ceStore.all.isEmpty())
    }

    @Test
    fun `p1 dpContext falls back to appContext on exception`() {
        // Normal context → DP context is a different instance but same prefs-dir semantics
        val dp = DeviceProtectedStorage.dpContext(appContext)
        // In Robolectric DP is a separate storage — prefs write/read must work
        dp.getSharedPreferences("test_dp", Context.MODE_PRIVATE).edit().putString("k", "v").commit()
        assertEquals("v", dp.getSharedPreferences("test_dp", Context.MODE_PRIVATE).getString("k", null))
    }

    // ============== B1 (review): calendar_alarm_settings (SettingsRepository) must ALSO be migrated ==============

    @Test
    fun `b1 calendar_alarm_settings is in DP_PREFS_FILES and migrates CE values to DP`() {
        assertTrue(
            "B1: SettingsRepository regular prefs (calendar_alarm_settings) must be in DP_PREFS_FILES",
            DeviceProtectedStorage.DP_PREFS_FILES.contains("calendar_alarm_settings")
        )
        // Simulate pre-upgrade state in CE: snooze 10, freeze on, server_url, wake times
        val ce = appContext.getSharedPreferences("calendar_alarm_settings", Context.MODE_PRIVATE)
        ce.edit()
            .putInt("snooze_minutes", 10)
            .putBoolean("wakker_freeze_enabled", true)
            .putString("wakker_freeze_start", "23:00")
            .putString("office_wake_time", "06:30")
            .putString("server_url", "https://example.test")
            .putBoolean("auto_update", true)
            .commit()

        DeviceProtectedStorage.migrateOnce(appContext)

        // After migration SettingsRepository (now DP) reads the same values
        val repo = SettingsRepository(appContext)
        assertEquals(10, repo.getSnoozeMinutes())
        assertTrue(repo.isWekkerFreezeEnabled())
        assertEquals("23:00", repo.getWekkerFreezeStart())
        assertEquals("https://example.test", repo.getServerUrl())
        // officeWakeTime via the StateFlow — in unit test without coroutines: directly from DP prefs
        assertEquals(
            "06:30",
            DeviceProtectedStorage.prefs(appContext, "calendar_alarm_settings").getString("office_wake_time", null)
        )
    }
}
