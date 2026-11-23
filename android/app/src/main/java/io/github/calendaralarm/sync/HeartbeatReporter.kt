package io.github.calendaralarm.sync

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.calendaralarm.BuildConfig
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.AlarmTransactions
import io.github.calendaralarm.data.AppClock
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.PermissionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 4 (hardening plan 2026-11-22, principle 7): heartbeat to the server —
 * "does the phone have the alarm the server expects?"
 *
 * Sends POST /api/heartbeat with:
 *  - the ENABLED, not-expired alarms from the store (consistent snapshot via
 *    [AlarmTransactions.run] — NO network inside the lock: snapshot first, send later)
 *  - the permission status ([PermissionStatus.read])
 *
 * Network goes through a per-run Ktor client with the pinned trust manager
 * ([CertificatePinner.pinnedTrustManager]) for the fixed host from the server URL —
 * same pattern as [LogUploadWorker] (B1: no shared global host).
 *
 * Errors are NEVER fatal: log + [HeartbeatResult(success=false)] — the caller
 * returns Result.success() to WorkManager so no retry storm occurs;
 * the next periodic run tries again.
 */
open class HeartbeatReporter(private val context: Context) {

    companion object {
        private const val TAG = "HeartbeatReporter"
    }

    @Serializable
    data class ScheduledEntryPayload(
        val date: String,
        val time: String,
        val enabled: Boolean,
        @SerialName("is_snooze") val isSnooze: Boolean,
        // P4 review B1: disabled by the user → server reports "user_disabled" (silent)
        @SerialName("user_disabled") val userDisabled: Boolean = false
    )

    @Serializable
    data class PermissionsPayload(
        val notifications: Boolean,
        @SerialName("full_screen") val fullScreen: Boolean,
        @SerialName("exact_alarms") val exactAlarms: Boolean,
        @SerialName("battery_unrestricted") val batteryUnrestricted: Boolean
    )

    @Serializable
    data class HeartbeatPayload(
        @SerialName("app_version") val appVersion: String,
        @SerialName("version_code") val versionCode: Int,
        @SerialName("sent_at") val sentAt: String,
        val scheduled: List<ScheduledEntryPayload>,
        val permissions: PermissionsPayload
    )

    data class HeartbeatResult(
        val success: Boolean,
        val error: String? = null,
        val scheduledCount: Int = 0
    )

    private val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
    }

    private val userAgent =
        "CalendarAlarm/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"

    /**
     * Build the payload WITHOUT network: snapshot of the store inside the alarm mutex
     * (short, read-only), read permission status, time via [AppClock].
     *
     * Only ENABLED and not-expired (triggerAtMillis > now) alarms are included —
     * a past alarm is no longer "scheduled" for the monitoring.
     */
    fun buildPayload(
        nowMillis: Long = AppClock.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): HeartbeatPayload {
        // P4: snapshot INSIDE the lock, no network inside the lock.
        val snapshot: List<AlarmStore.OwnedAlarm> = AlarmTransactions.run {
            AlarmStore(context).getAll()
                // enabled + future, OR disabled by the user (deliberate choice — the
                // server must be able to distinguish this from a vanished alarm)
                .filter { (it.enabled || it.userDisabled) && it.triggerAtMillis > nowMillis }
                .sortedBy { it.triggerAtMillis }
        }
        val entries = snapshot.map { a ->
            // P4 review: the stored target date (same value the sync uses); only
            // derive it from the trigger time when the value is invalid.
            val date = if (Regex("""\d{4}-\d{2}-\d{2}""").matches(a.date)) a.date else try {
                LocalDate.ofInstant(Instant.ofEpochMilli(a.triggerAtMillis), zone).toString()
            } catch (e: Exception) { a.date }
            // P4 review (privacy): do not send labels — the server does not use them
            ScheduledEntryPayload(
                date = date,
                time = a.timeLabel,
                enabled = a.enabled,
                isSnooze = a.isSnooze,
                userDisabled = a.userDisabled && !a.enabled
            )
        }.take(50) // server max 50
        val perms = PermissionStatus.read(context)
        return HeartbeatPayload(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            sentAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMillis)),
            scheduled = entries,
            permissions = PermissionsPayload(
                notifications = perms.notifications,
                fullScreen = perms.fullScreen,
                exactAlarms = perms.exactAlarms,
                batteryUnrestricted = perms.batteryUnrestricted
            )
        )
    }

    /**
     * Opus review B1: client per run with fixed host from the server URL
     * (fail-closed when there is no host) — pinned trust manager, same as LogUploadWorker.
     */
    private fun buildClient(serverUrl: String): HttpClient {
        val host = CertificatePins.extractHost(serverUrl)
            ?: throw IllegalArgumentException("Invalid server URL (no host): $serverUrl — heartbeat refused (fail-closed)")
        return HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            followRedirects = false
            engine {
                requestTimeout = 15_000
                https {
                    trustManager = CertificatePinner.pinnedTrustManager(host)
                }
            }
        }
    }

    /**
     * Send the heartbeat to the server. NEVER returns an exception — errors
     * are logged and returned as [HeartbeatResult(success=false)].
     */
    open suspend fun send(): HeartbeatResult = withContext(Dispatchers.IO) {
        val settingsRepo = CalendarAlarmApp.settingsRepository
        if (!settingsRepo.secureStorageAvailable) {
            AppLogger.w(TAG, "Heartbeat skipped: secure storage not available")
            return@withContext HeartbeatResult(false, "Secure storage not available")
        }
        val payload = try {
            buildPayload()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Building heartbeat payload failed: ${e.message}")
            return@withContext HeartbeatResult(false, "Payload error: ${e.javaClass.simpleName}")
        }
        return@withContext try {
            val token = settingsRepo.getDeviceToken()
            val serverUrl = settingsRepo.getServerUrl()
            CertificatePins.extractHost(serverUrl)
                ?: throw IllegalArgumentException("Invalid server URL: $serverUrl")
            val client = buildClient(serverUrl)
            try {
                val url = "${serverUrl.trimEnd('/')}/api/heartbeat"
                AppLogger.d(TAG, "POST $url (${payload.scheduled.size} alarms)")
                val response = client.post(url) {
                    header("Authorization", "Bearer $token")
                    header("User-Agent", userAgent)
                    header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    if (settingsRepo.hasCfAccessCredentials()) {
                        header("Cf-Access-Client-Id", settingsRepo.getCfAccessClientId())
                        header("Cf-Access-Client-Secret", settingsRepo.getCfAccessClientSecret())
                    }
                    setBody(json.encodeToString(HeartbeatPayload.serializer(), payload))
                }
                if (response.status.isSuccess()) {
                    AppLogger.i(TAG, "Heartbeat OK — ${payload.scheduled.size} alarms reported")
                    HeartbeatResult(true, scheduledCount = payload.scheduled.size)
                } else {
                    val body = try { response.bodyAsText().take(120) } catch (e: Exception) { "" }
                    AppLogger.w(TAG, "Heartbeat refused: HTTP ${response.status.value} — $body")
                    HeartbeatResult(false, "HTTP ${response.status.value}", payload.scheduled.size)
                }
            } finally {
                runCatching { client.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // P4: never fatal — just log; the next periodic run tries again.
            AppLogger.w(TAG, "Heartbeat error (not fatal): ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            HeartbeatResult(false, "Network error: ${e.javaClass.simpleName}", payload.scheduled.size)
        }
    }
}
