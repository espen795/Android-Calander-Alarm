package io.github.calendaralarm.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Settings & secure token storage.
 *
 * SEC-02 / SEC-11: Sensitive data (tokens, CF credentials) uses EncryptedSharedPreferences
 * with Android Keystore MasterKey.
 *
 * v2.5.10 CRITICAL FIX: Non-sensitive settings (snooze, freeze, wake times) now use
 * REGULAR SharedPreferences. EncryptedSharedPreferences can fail on Samsung devices
 * (Keystore issues), which caused ALL settings to be lost. Regular prefs are reliable
 * and these settings are not sensitive (no security risk).
 *
 * FAIL CLOSED still applies to SECURE storage: if EncryptedSharedPreferences fails,
 * tokens/CF credentials are unavailable (API disabled), but user settings still work.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "SettingsRepository"
        private const val SECURE_PREFS_FILE = "calendar_alarm_secure_prefs"
        private const val REGULAR_PREFS_FILE = "calendar_alarm_settings"  // v2.5.10: non-sensitive settings
        private const val KEY_OFFICE_TIME = "office_wake_time"
        private const val KEY_HOME_TIME = "home_wake_time"
        private const val KEY_OFF_TIME = "off_wake_time"
        private const val KEY_OFFICE_DAYS = "office_days"
        private const val KEY_HOME_DAYS = "home_days"
        private const val KEY_OFF_DAYS = "off_days"
        private const val KEY_UPDATE_INTERVAL = "update_interval_hours"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LAST_SYNC_RESULT = "last_sync_result"
        private const val KEY_CF_CLIENT_ID = "cf_access_client_id"
        private const val KEY_CF_CLIENT_SECRET = "cf_access_client_secret"
        private const val KEY_SNOOZE_MINUTES = "snooze_minutes"  // F6
        // v2.5.7 Story 3: Alarm Freeze — block alarm changes during configured hours
        private const val KEY_WAKKER_FREEZE_ENABLED = "wakker_freeze_enabled"
        private const val KEY_WAKKER_FREEZE_START = "wakker_freeze_start"
        private const val KEY_WAKKER_FREEZE_END = "wakker_freeze_end"
    }

    /**
     * True only when EncryptedSharedPreferences initialized successfully.
     * When false, the API server must not start (fail closed) — but user settings still work.
     */
    val secureStorageAvailable: Boolean
    private val securePrefs: SharedPreferences?

    // v2.5.10: Regular SharedPreferences for non-sensitive settings (always available)
    private val regularPrefs: SharedPreferences = context.getSharedPreferences(REGULAR_PREFS_FILE, Context.MODE_PRIVATE)

    init {
        var prefs: SharedPreferences? = null
        var available = false
        try {
            val app = context.applicationContext
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build() // propagate Keystore error — NO fallback
            prefs = EncryptedSharedPreferences.create(
                app,
                SECURE_PREFS_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            available = true
            Log.i(TAG, "Secure storage initialized successfully")
        } catch (e: Exception) {
            // FAIL CLOSED for secure storage: tokens/CF credentials unavailable
            // But regular settings still work (v2.5.10 fix)
            Log.e(TAG, "Secure storage initialization failed — tokens/CF creds disabled, but user settings still work")
            prefs = null
            available = false
        }
        securePrefs = prefs
        secureStorageAvailable = available
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: Flow<AlarmSettings> = _settings.asStateFlow()

    private fun loadSettings(): AlarmSettings {
        // v2.5.10: Load non-sensitive settings from REGULAR prefs (reliable on all devices)
        return AlarmSettings(
            officeWakeTime = regularPrefs.getString(KEY_OFFICE_TIME, "07:00") ?: "07:00",
            homeWakeTime = regularPrefs.getString(KEY_HOME_TIME, "08:30") ?: "08:30",
            offWakeTime = regularPrefs.getString(KEY_OFF_TIME, "09:00") ?: "09:00",
            officeDays = regularPrefs.getString(KEY_OFFICE_DAYS, "1,2,3,4,5")?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(1, 2, 3, 4, 5),
            homeDays = regularPrefs.getString(KEY_HOME_DAYS, "1,2,3,4,5")?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(1, 2, 3, 4, 5),
            offDays = regularPrefs.getString(KEY_OFF_DAYS, "6,7")?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(6, 7),
            updateCheckIntervalHours = regularPrefs.getInt(KEY_UPDATE_INTERVAL, 6),
            autoUpdateEnabled = regularPrefs.getBoolean(KEY_AUTO_UPDATE, false),
            snoozeMinutes = regularPrefs.getInt(KEY_SNOOZE_MINUTES, 5),  // F6
            // v2.5.7 Story 3: Alarm Freeze settings (default: disabled)
            wakkerFreezeEnabled = regularPrefs.getBoolean(KEY_WAKKER_FREEZE_ENABLED, false),
            wakkerFreezeStart = regularPrefs.getString(KEY_WAKKER_FREEZE_START, "22:00") ?: "22:00",
            wakkerFreezeEnd = regularPrefs.getString(KEY_WAKKER_FREEZE_END, "07:00") ?: "07:00"
        )
    }

    fun updateSettings(newSettings: AlarmSettings) {
        if (!newSettings.isValid()) {
            Log.w(TAG, "Rejected invalid settings")
            return
        }
        // v2.5.10: Save non-sensitive settings to REGULAR prefs (reliable)
        regularPrefs.edit().apply {
            putString(KEY_OFFICE_TIME, newSettings.officeWakeTime)
            putString(KEY_HOME_TIME, newSettings.homeWakeTime)
            putString(KEY_OFF_TIME, newSettings.offWakeTime)
            putString(KEY_OFFICE_DAYS, newSettings.officeDays.joinToString(","))
            putString(KEY_HOME_DAYS, newSettings.homeDays.joinToString(","))
            putString(KEY_OFF_DAYS, newSettings.offDays.joinToString(","))
            putInt(KEY_UPDATE_INTERVAL, newSettings.updateCheckIntervalHours)
            putBoolean(KEY_AUTO_UPDATE, newSettings.autoUpdateEnabled)
            putInt(KEY_SNOOZE_MINUTES, newSettings.snoozeMinutes)  // F6
            // v2.5.7 Story 3: Save Alarm Freeze settings
            putBoolean(KEY_WAKKER_FREEZE_ENABLED, newSettings.wakkerFreezeEnabled)
            putString(KEY_WAKKER_FREEZE_START, newSettings.wakkerFreezeStart)
            putString(KEY_WAKKER_FREEZE_END, newSettings.wakkerFreezeEnd)
            apply()
        }
        _settings.value = newSettings
    }

    /**
     * F6: Get the configured snooze duration in minutes (default 5).
     * v2.5.0: Returns 0 when snooze is disabled ("Off").
     * v2.5.10: Reads from regular SharedPreferences (reliable).
     */
    fun getSnoozeMinutes(): Int {
        return regularPrefs.getInt(KEY_SNOOZE_MINUTES, 5)
    }

    /**
     * F6: Set the snooze duration in minutes. Must be one of 0 (off), 5, 10, 15, 30.
     * v2.5.0: Added 0 = "Off" (no snooze button shown when alarm rings).
     * v2.5.10: Saves to regular SharedPreferences (reliable on Samsung).
     */
    fun setSnoozeMinutes(minutes: Int): Boolean {
        if (minutes !in listOf(0, 5, 10, 15, 30)) {
            Log.w(TAG, "Rejected invalid snooze minutes: $minutes")
            return false
        }
        val ok = regularPrefs.edit().putInt(KEY_SNOOZE_MINUTES, minutes).commit()
        if (ok) {
            // Update StateFlow so UI reflects the change immediately
            _settings.value = _settings.value.copy(snoozeMinutes = minutes)
            Log.i(TAG, "Snooze minutes updated: $minutes (persisted to regular prefs)")
        } else {
            Log.e(TAG, "FAILED to persist snooze minutes: $minutes")
        }
        return ok
    }

    // ============== v2.5.7 Story 3: Alarm Freeze ==============

    /**
     * v2.5.7 Story 3: Check if Alarm Freeze is enabled.
     * v2.5.10: Reads from regular SharedPreferences.
     */
    fun isWekkerFreezeEnabled(): Boolean {
        return regularPrefs.getBoolean(KEY_WAKKER_FREEZE_ENABLED, false)
    }

    /**
     * v2.5.7 Story 3: Get the freeze start time ("HH:mm", default "22:00").
     * v2.5.10: Reads from regular SharedPreferences.
     */
    fun getWekkerFreezeStart(): String {
        return regularPrefs.getString(KEY_WAKKER_FREEZE_START, "22:00") ?: "22:00"
    }

    /**
     * v2.5.7 Story 3: Get the freeze end time ("HH:mm", default "07:00").
     * v2.5.10: Reads from regular SharedPreferences.
     */
    fun getWekkerFreezeEnd(): String {
        return regularPrefs.getString(KEY_WAKKER_FREEZE_END, "07:00") ?: "07:00"
    }

    /**
     * v2.5.7 Story 3: Check if alarm changes are currently frozen.
     * Returns true if freeze is enabled AND current time is within the freeze window.
     * Handles overnight windows (e.g., 22:00-07:00 crosses midnight).
     */
    fun isWekkerFrozen(): Boolean {
        if (!isWekkerFreezeEnabled()) return false
        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

        val startParts = getWekkerFreezeStart().split(":")
        val endParts = getWekkerFreezeEnd().split(":")
        val startMinutes = startParts[0].toIntOrNull()?.times(60)?.plus(startParts[1].toIntOrNull() ?: 0) ?: return false
        val endMinutes = endParts[0].toIntOrNull()?.times(60)?.plus(endParts[1].toIntOrNull() ?: 0) ?: return false

        return if (startMinutes <= endMinutes) {
            // Same-day window (e.g., 09:00-17:00)
            currentMinutes in startMinutes until endMinutes
        } else {
            // Overnight window (e.g., 22:00-07:00 crosses midnight)
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    /**
     * v2.5.7 Story 3: Get the freeze message for user display.
     * Format: "Alarms are frozen between {start} and {end}"
     */
    fun getWekkerFreezeMessage(): String {
        return "Alarms are frozen between ${getWekkerFreezeStart()} and ${getWekkerFreezeEnd()}"
    }

    /**
     * v2.5.7 Story 3: Update the Alarm Freeze settings.
     * Validates time format (HH:mm) before saving.
     * v2.5.10: Saves to regular SharedPreferences (reliable on Samsung).
     */
    fun updateWekkerFreeze(enabled: Boolean, start: String, end: String): Boolean {
        val timeRegex = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        if (!timeRegex.matches(start) || !timeRegex.matches(end)) {
            Log.w(TAG, "Rejected invalid freeze times: start=$start end=$end")
            return false
        }
        val ok = regularPrefs.edit()
            .putBoolean(KEY_WAKKER_FREEZE_ENABLED, enabled)
            .putString(KEY_WAKKER_FREEZE_START, start)
            .putString(KEY_WAKKER_FREEZE_END, end)
            .commit()
        if (ok) {
            // Update StateFlow so UI reflects the change immediately
            _settings.value = _settings.value.copy(
                wakkerFreezeEnabled = enabled,
                wakkerFreezeStart = start,
                wakkerFreezeEnd = end
            )
            Log.i(TAG, "Alarm Freeze updated: enabled=$enabled $start-$end (persisted to regular prefs)")
        } else {
            Log.e(TAG, "FAILED to persist Alarm Freeze settings")
        }
        return ok
    }

    // ============== Token Generation ==============

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    // ============== Update info cache (regular prefs — not sensitive) ==============

    fun saveLastUpdateCheck(versionInfoJson: String) {
        regularPrefs.edit().putString(KEY_LAST_UPDATE_CHECK, versionInfoJson).apply()
    }

    fun getLastUpdateCheck(): String? = regularPrefs.getString(KEY_LAST_UPDATE_CHECK, null)

    // ============== Sync / Server Configuration ==============

    /**
     * Device token used to authenticate with the Calendar Alarm server (pull API).
     * Generated on first use, stored in EncryptedSharedPreferences (SECURE).
     */
    fun getDeviceToken(): String {
        val p = securePrefs ?: throw IllegalStateException("Secure storage unavailable")
        val existing = p.getString(KEY_DEVICE_TOKEN, null)
        if (!existing.isNullOrEmpty()) return existing
        // Auto-generate if not set (user can overwrite via setDeviceToken)
        val token = generateSecureToken()
        val written = p.edit().putString(KEY_DEVICE_TOKEN, token).commit()
        if (!written) throw IllegalStateException("Failed to persist device token")
        return token
    }

    /**
     * Set a user-provided device token (e.g. copied from the backend server config).
     * The server must have this exact token configured.
     * Stored in EncryptedSharedPreferences (SECURE).
     */
    fun setDeviceToken(token: String): Boolean {
        val p = securePrefs ?: return false
        val trimmed = token.trim()
        if (trimmed.length < 32) {
            Log.w(TAG, "Device token too short (min 32 chars)")
            return false
        }
        return p.edit().putString(KEY_DEVICE_TOKEN, trimmed).commit()
    }

    fun rotateDeviceToken(): String {
        val p = securePrefs ?: throw IllegalStateException("Secure storage unavailable")
        val newToken = generateSecureToken()
        val written = p.edit().putString(KEY_DEVICE_TOKEN, newToken).commit()
        if (!written) throw IllegalStateException("Failed to persist rotated device token")
        return newToken
    }

    /**
     * Base URL of the Calendar Alarm pull API server (HTTPS).
     * v2.5.10: Stored in regular SharedPreferences (not sensitive).
     */
    fun getServerUrl(): String {
        return regularPrefs.getString(KEY_SERVER_URL, io.github.calendaralarm.BuildConfig.ALARM_API_URL)
            ?: io.github.calendaralarm.BuildConfig.ALARM_API_URL
    }

    fun setServerUrl(url: String) {
        // Validate HTTPS
        if (!url.startsWith("https://")) {
            Log.w(TAG, "Rejected non-HTTPS server URL")
            return
        }
        regularPrefs.edit().putString(KEY_SERVER_URL, url).commit()
    }

    /**
     * Last sync result for UI display.
     * v2.5.10: Stored in regular SharedPreferences (not sensitive).
     */
    fun saveLastSyncResult(resultJson: String) {
        regularPrefs.edit().putString(KEY_LAST_SYNC_RESULT, resultJson).apply()
    }

    fun getLastSyncResult(): String? = regularPrefs.getString(KEY_LAST_SYNC_RESULT, null)

    // ============== Cloudflare Access Service Token (SECURE) ==============
    // Used to authenticate with Cloudflare Access-protected endpoints
    // (API backend and update server) without a browser login.
    // Stored in EncryptedSharedPreferences (SECURE).

    fun getCfAccessClientId(): String {
        return securePrefs?.getString(KEY_CF_CLIENT_ID, "") ?: ""
    }

    fun getCfAccessClientSecret(): String {
        return securePrefs?.getString(KEY_CF_CLIENT_SECRET, "") ?: ""
    }

    fun hasCfAccessCredentials(): Boolean {
        val p = securePrefs ?: return false
        val id = p.getString(KEY_CF_CLIENT_ID, "") ?: ""
        val secret = p.getString(KEY_CF_CLIENT_SECRET, "") ?: ""
        return id.isNotEmpty() && secret.isNotEmpty()
    }

    fun setCfAccessCredentials(clientId: String, clientSecret: String): Boolean {
        val p = securePrefs ?: return false
        // Validate format before storing
        if (clientId.isNotBlank() && !clientId.endsWith(".access")) {
            Log.w(TAG, "CF Access Client ID has unexpected format")
            return false
        }
        if (clientSecret.isNotBlank() && !clientSecret.startsWith("cfast_")) {
            Log.w(TAG, "CF Access Client Secret has unexpected format")
            return false
        }
        return p.edit()
            .putString(KEY_CF_CLIENT_ID, clientId.trim())
            .putString(KEY_CF_CLIENT_SECRET, clientSecret.trim())
            .commit()
    }

    fun clearCfAccessCredentials(): Boolean {
        val p = securePrefs ?: return false
        return p.edit()
            .remove(KEY_CF_CLIENT_ID)
            .remove(KEY_CF_CLIENT_SECRET)
            .commit()
    }

    // ============== v2.5.10: Migration from encrypted to regular prefs ==============

    /**
     * v2.5.10: Migrate non-sensitive settings from EncryptedSharedPreferences to regular
     * SharedPreferences. Called once on first launch after update.
     * If user had settings in encrypted prefs (from v2.5.9 and earlier), copy them over.
     */
    fun migrateFromEncryptedPrefs(): Boolean {
        val p = securePrefs ?: return false // No encrypted prefs = nothing to migrate

        // Check if we already migrated (marker in regular prefs)
        if (regularPrefs.getBoolean("migrated_from_encrypted_v2510", false)) {
            return true // Already migrated
        }

        Log.i(TAG, "Migrating settings from encrypted to regular SharedPreferences (v2.5.10)")

        try {
            val editor = regularPrefs.edit()

            // Migrate wake times
            p.getString(KEY_OFFICE_TIME, null)?.let { editor.putString(KEY_OFFICE_TIME, it) }
            p.getString(KEY_HOME_TIME, null)?.let { editor.putString(KEY_HOME_TIME, it) }
            p.getString(KEY_OFF_TIME, null)?.let { editor.putString(KEY_OFF_TIME, it) }

            // Migrate days
            p.getString(KEY_OFFICE_DAYS, null)?.let { editor.putString(KEY_OFFICE_DAYS, it) }
            p.getString(KEY_HOME_DAYS, null)?.let { editor.putString(KEY_HOME_DAYS, it) }
            p.getString(KEY_OFF_DAYS, null)?.let { editor.putString(KEY_OFF_DAYS, it) }

            // Migrate snooze
            if (p.contains(KEY_SNOOZE_MINUTES)) {
                editor.putInt(KEY_SNOOZE_MINUTES, p.getInt(KEY_SNOOZE_MINUTES, 5))
            }

            // Migrate Alarm Freeze
            if (p.contains(KEY_WAKKER_FREEZE_ENABLED)) {
                editor.putBoolean(KEY_WAKKER_FREEZE_ENABLED, p.getBoolean(KEY_WAKKER_FREEZE_ENABLED, false))
            }
            p.getString(KEY_WAKKER_FREEZE_START, null)?.let { editor.putString(KEY_WAKKER_FREEZE_START, it) }
            p.getString(KEY_WAKKER_FREEZE_END, null)?.let { editor.putString(KEY_WAKKER_FREEZE_END, it) }

            // Migrate server URL (not sensitive)
            p.getString(KEY_SERVER_URL, null)?.let { editor.putString(KEY_SERVER_URL, it) }

            // Migrate last sync result
            p.getString(KEY_LAST_SYNC_RESULT, null)?.let { editor.putString(KEY_LAST_SYNC_RESULT, it) }
            p.getString(KEY_LAST_UPDATE_CHECK, null)?.let { editor.putString(KEY_LAST_UPDATE_CHECK, it) }

            // Mark as migrated
            editor.putBoolean("migrated_from_encrypted_v2510", true)

            val ok = editor.commit()
            if (ok) {
                Log.i(TAG, "Migration complete: settings moved from encrypted to regular prefs")
                // Reload settings into StateFlow
                _settings.value = loadSettings()
            } else {
                Log.e(TAG, "Migration FAILED: could not write to regular prefs")
            }
            return ok

        } catch (e: Exception) {
            Log.e(TAG, "Migration failed: ${e.message}")
            return false
        }
    }
}
