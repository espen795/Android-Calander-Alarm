package io.github.calendaralarm.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AppLogger

/**
 * Periodically uploads the in-app log buffer to the the server server so the server
 * can debug remotely. Only sends sanitized entries (AppLogger never contains
 * tokens or personal data). Sends only NEW entries since last successful upload.
 */
class LogUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LogUploadWorker"
        const val WORK_NAME = "calendar_alarm_log_upload"
        private const val PREFS_FILE = "calalarm_log_upload_state"
        private const val KEY_LAST_SENT_COUNT = "last_sent_count"
        private const val MAX_ENTRIES_PER_BATCH = 200
    }

    @Serializable
    data class LogEntryPayload(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String
    )

    @Serializable
    data class LogUploadPayload(val entries: List<LogEntryPayload>)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Opus review B1: client per run (fixed host from the server URL) — no shared
     * global expectedHost. Built in doWork() and closed in finally.
     */
    private fun buildClient(serverUrl: String): HttpClient {
        val host = io.github.calendaralarm.sync.CertificatePins.extractHost(serverUrl)
            ?: throw IllegalArgumentException("Invalid server URL (no host): $serverUrl — log upload refused (fail-closed)")
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            followRedirects = false
            engine {
                requestTimeout = 15_000
                https {
                    // Pinning + hostname verification with the FIXED host (B1)
                    trustManager = io.github.calendaralarm.sync.CertificatePinner.pinnedTrustManager(host)
                }
            }
        }
    }

    private val userAgent = "CalendarAlarm/${io.github.calendaralarm.BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"

    /** Client of the current run (closed in finally). */
    @Volatile
    private var clientInFlight: HttpClient? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settingsRepo = CalendarAlarmApp.settingsRepository
        if (!settingsRepo.secureStorageAvailable) return@withContext Result.success()

        val allEntries = AppLogger.getAll()
        if (allEntries.isEmpty()) return@withContext Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val lastSent = prefs.getInt(KEY_LAST_SENT_COUNT, 0)

        // Only send new entries
        val newEntries = if (allEntries.size > lastSent) {
            allEntries.subList(lastSent.coerceAtMost(allEntries.size), allEntries.size)
        } else {
            // Log was cleared — reset counter
            prefs.edit().putInt(KEY_LAST_SENT_COUNT, 0).apply()
            allEntries
        }

        if (newEntries.isEmpty()) return@withContext Result.success()

        // Take at most MAX_ENTRIES_PER_BATCH
        val batch = newEntries.takeLast(MAX_ENTRIES_PER_BATCH)
        val totalSent = (lastSent + batch.size).coerceAtMost(allEntries.size + batch.size)

        val payload = LogUploadPayload(
            batch.map { LogEntryPayload(it.timestamp, it.level.name, it.tag, it.message) }
        )

        return@withContext try {
            val token = settingsRepo.getDeviceToken()
            val serverUrl = settingsRepo.getServerUrl()
            // B1: client per run with the fixed host from the server URL (fail-closed on no host)
            val httpClient = buildClient(serverUrl)
            clientInFlight = httpClient
            val url = "${serverUrl.trimEnd('/')}/api/log"
            val response = httpClient.post(url) {
                // K-03 fix: Token in Authorization header, NOT in URL query parameter
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                header("User-Agent", userAgent)
                if (settingsRepo.hasCfAccessCredentials()) {
                    header("Cf-Access-Client-Id", settingsRepo.getCfAccessClientId())
                    header("Cf-Access-Client-Secret", settingsRepo.getCfAccessClientSecret())
                }
                setBody(json.encodeToString(LogUploadPayload.serializer(), payload))
            }

            if (response.status.isSuccess()) {
                // Mark as sent
                val allCount = AppLogger.getAll().size
                prefs.edit().putInt(KEY_LAST_SENT_COUNT, allCount).apply()
                AppLogger.d(TAG, "Uploaded ${batch.size} log entries")
                Result.success()
            } else if (response.status == HttpStatusCode.Unauthorized) {
                AppLogger.w(TAG, "Log upload rejected (401) — check device token")
                Result.success() // don't retry hammering
            } else {
                AppLogger.w(TAG, "Log upload failed: HTTP ${response.status.value}")
                Result.retry()
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Log upload error: ${e.javaClass.simpleName}")
            Result.retry()
        } finally {
            // B1: client is built per run in doWork — close it here
            runCatching { clientInFlight?.close() }
            clientInFlight = null
        }
    }
}
