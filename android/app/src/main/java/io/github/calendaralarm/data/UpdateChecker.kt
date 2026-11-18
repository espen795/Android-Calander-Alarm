package io.github.calendaralarm.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.ByteReadChannel
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import io.github.calendaralarm.BuildConfig
import io.github.calendaralarm.data.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest

/**
 * Handles app self-updates: checks for new versions and downloads/verifies APKs.
 *
 * Security fixes applied (see REVIEW_REPORT.md):
 * - C-03: SHA-256 is REQUIRED (64 hex), size is REQUIRED and bounded (max 50MB).
 * - C-03: APK signing certificate is verified against the pinned release cert AND
 *   the currently installed app's signer before any install intent is created.
 * - H-02: Download URL is strictly validated (relative path on expected host, no redirects).
 * - H-02: Download is streamed with a hard byte cap.
 * - M-02: PackageInfo.longVersionCode guarded for API < 28.
 * - M-11: Network errors are returned as errors, never as "no update".
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val MAX_APK_SIZE = 50L * 1024 * 1024 // 50 MB

        /**
         * Optional pinned SHA-256 fingerprint of the release signing certificate,
         * set at build time via the CALALARM_UPDATE_SIGNER_SHA256 Gradle property
         * (`keytool -list -v -keystore release.jks` → SHA256, without colons).
         * Empty = skip the pin; the downloaded APK must still match the signer of
         * the installed app, which Android enforces for updates anyway.
         */
        val PINNED_SIGNER_SHA256: String = BuildConfig.UPDATE_SIGNER_SHA256.replace(":", "").lowercase()

        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val APK_PATH_REGEX = Regex("^/releases/calendar-alarm-v[0-9A-Za-z._-]{1,64}\\.apk$")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        // H-02: never follow redirects for update downloads
        followRedirects = false
        engine {
            requestTimeout = 60_000 // APK downloads can take a while
            // Certificate pinning (same as SyncRepository — Ktor CIO doesn't use Android NSC)
            https {
                trustManager = io.github.calendaralarm.sync.CertificatePinner.trustManager
            }
        }
    }

    private val userAgent = "CalendarAlarm/${io.github.calendaralarm.BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    /**
     * Add standard headers (User-Agent, Accept, Cloudflare Access) to a request.
     */
    private fun addHeaders(builder: io.ktor.client.request.HttpRequestBuilder) {
        with(builder) {
            header("User-Agent", userAgent)
            header("Accept", "application/json, application/vnd.android.package-archive")
            val settingsRepo = io.github.calendaralarm.CalendarAlarmApp.settingsRepository
            if (settingsRepo.hasCfAccessCredentials()) {
                header("Cf-Access-Client-Id", settingsRepo.getCfAccessClientId())
                header("Cf-Access-Client-Secret", settingsRepo.getCfAccessClientSecret())
            }
        }
    }

    data class CheckResult(
        val updateAvailable: Boolean,
        val currentVersion: String,
        val currentVersionCode: Int,
        val latestVersion: String? = null,
        val latestVersionCode: Int = 0,
        val downloadUrl: String? = null,
        val changelog: String? = null,
        val sha256: String? = null,
        val size: Long = 0,
        val error: String? = null
    )

    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "checkForUpdate() called")
        
        // v2.5.9 Story 1: Warn if CF Access credentials are missing
        val settingsRepo = io.github.calendaralarm.CalendarAlarmApp.settingsRepository
        if (!settingsRepo.hasCfAccessCredentials()) {
            AppLogger.w(TAG, "CF Access credentials NOT configured — update check will likely fail (302 redirect)")
        }
        
        try {
            if (BuildConfig.UPDATE_SERVER_URL.isBlank()) {
                return@withContext CheckResult(
                    updateAvailable = false,
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    error = "Update server not configured"
                )
            }
            val url = "${BuildConfig.UPDATE_SERVER_URL}/version.json"
            AppLogger.d(TAG, "GET $url")

            val response: HttpResponse = httpClient.get(url) {
                addHeaders(this)
            }
            AppLogger.d(TAG, "Update check response: HTTP ${response.status.value}")

            if (!response.status.isSuccess()) {
                // v2.5.9 Story 1: Detailed, actionable error messages based on HTTP status
                val errorMsg = when (response.status.value) {
                    301, 302, 307, 308 -> "Server redirect (302) — Cloudflare Access login required"
                    401 -> "Not authorized (401) — check Cloudflare Access credentials"
                    403 -> "Forbidden (403) — Cloudflare blocks, check Client ID + Secret"
                    404 -> "Not found (404) — server URL possibly wrong"
                    in 500..599 -> "Server error (${response.status.value}) — try again later"
                    else -> "Server returned ${response.status.value}"
                }
                AppLogger.e(TAG, "Update check HTTP error: ${response.status.value} -> $errorMsg")
                return@withContext CheckResult(
                    updateAvailable = false,
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    error = errorMsg
                )
            }

            val versionInfo = json.decodeFromString<VersionInfo>(response.bodyAsText())

            // H-02: validate metadata strictly
            if (!SHA256_REGEX.matches(versionInfo.sha256)) {
                return@withContext CheckResult(
                    updateAvailable = false,
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    error = "Invalid SHA-256 in version.json"
                )
            }
            if (versionInfo.size !in 1L..MAX_APK_SIZE) {
                return@withContext CheckResult(
                    updateAvailable = false,
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    error = "Invalid APK size in version.json"
                )
            }
            if (versionInfo.minSdk > Build.VERSION.SDK_INT) {
                return@withContext CheckResult(
                    updateAvailable = false,
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    error = null
                )
            }
            if (!APK_PATH_REGEX.matches(versionInfo.url)) {
                return@withContext CheckResult(
                    updateAvailable = false,
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    error = "Invalid download path in version.json"
                )
            }

            val downloadUrl = try {
                val uri = URI(BuildConfig.UPDATE_SERVER_URL).resolve(versionInfo.url)
                if (uri.scheme != "https" || uri.host != URI(BuildConfig.UPDATE_SERVER_URL).host || uri.rawUserInfo != null) {
                    return@withContext CheckResult(
                        updateAvailable = false,
                        currentVersion = BuildConfig.VERSION_NAME,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        error = "Invalid download URL origin"
                    )
                }
                uri.toString()
            } catch (e: Exception) {
                return@withContext CheckResult(
                    updateAvailable = false,
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    error = "Invalid download URL"
                )
            }

            val updateAvailable = versionInfo.versionCode > BuildConfig.VERSION_CODE

            CheckResult(
                updateAvailable = updateAvailable,
                currentVersion = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
                latestVersion = versionInfo.version,
                latestVersionCode = versionInfo.versionCode,
                downloadUrl = if (updateAvailable) downloadUrl else null,
                changelog = versionInfo.changelog,
                sha256 = versionInfo.sha256,
                size = versionInfo.size
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            AppLogger.e(TAG, "Update check SSL error: ${e.message?.take(100)}")
            CheckResult(false, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, error = "SSL/certificate error")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Update check failed: ${e.javaClass.simpleName}")
            CheckResult(
                updateAvailable = false,
                currentVersion = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
                error = "Network error"
            )
        }
    }

    /**
     * Download the APK update to a unique temp file, enforcing the size cap and
     * verifying the SHA-256 checksum. Returns null on any failure (file deleted).
     */
    suspend fun downloadUpdate(downloadUrl: String, expectedSha256: String, expectedSize: Long): File? = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "downloadUpdate() called (expected ${expectedSize} bytes)")
        if (!SHA256_REGEX.matches(expectedSha256)) {
            AppLogger.e(TAG, "Invalid expected SHA-256 format")
            return@withContext null
        }
        if (expectedSize !in 1L..MAX_APK_SIZE) {
            AppLogger.e(TAG, "Invalid expected size: $expectedSize")
            return@withContext null
        }

        val updateDir = File(context.cacheDir, "apk_updates").apply { mkdirs() }
        val apkFile = File(updateDir, "update-${System.currentTimeMillis()}.apk")

        try {
            AppLogger.d(TAG, "Downloading APK...")
            val response: HttpResponse = httpClient.get(downloadUrl) {
                addHeaders(this)
            }

            if (!response.status.isSuccess()) {
                AppLogger.e(TAG, "APK download failed: HTTP ${response.status.value}")
                return@withContext null
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            var aborted = false

            // H-03 fix: Stream APK to file without loading it fully into memory
            // Prevents OOM on large APKs (was: bodyAsBytes() = 50MB+ in RAM)
            apkFile.parentFile?.mkdirs()
            FileOutputStream(apkFile).use { output ->
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    totalBytes += read

                    // Early abort when file gets too large (max 50MB)
                    if (totalBytes > MAX_APK_SIZE) {
                        AppLogger.e(TAG, "Download exceeds max size: $totalBytes > $MAX_APK_SIZE")
                        aborted = true
                        break
                    }
                }
                output.flush()
            }

            if (aborted) {
                apkFile.delete()
                return@withContext null
            }

            // H-02: hard cap check (now redundant but as extra verification)
            if (totalBytes > expectedSize || totalBytes > MAX_APK_SIZE) {
                AppLogger.e(TAG, "Download exceeds expected size: $totalBytes > $expectedSize")
                apkFile.delete()
                return@withContext null
            }

            // Verify exact size
            if (totalBytes != expectedSize) {
                AppLogger.e(TAG, "Size mismatch: expected $expectedSize, got $totalBytes")
                apkFile.delete()
                return@withContext null
            }

            // SHA-256 already computed during streaming (digest is updated in loop)
            val actualBytes = digest.digest()
            val expectedBytes = expectedSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (!MessageDigest.isEqual(actualBytes, expectedBytes)) {
                AppLogger.e(TAG, "SHA-256 mismatch! Download may be tampered.")
                apkFile.delete()
                return@withContext null
            }

            AppLogger.i(TAG, "APK downloaded + SHA-256 verified (${apkFile.length()} bytes)")
            return@withContext apkFile

        } catch (e: CancellationException) {
            apkFile.delete()
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download failed: ${e.javaClass.simpleName}")
            apkFile.delete()
            return@withContext null
        }
    }

    /**
     * C-03: Verify the downloaded APK's signing certificate against the pinned
     * release certificate AND the currently installed app's signer.
     * Also checks package name and version code.
     */
    fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager

            // Get archive info (package name + version)
            val archiveInfo: PackageInfo? = getPackageArchiveInfo(pm, apkFile.absolutePath, 0)
            if (archiveInfo == null) {
                Log.e(TAG, "Could not read archive info")
                return false
            }

            // Check package name matches
            if (archiveInfo.packageName != context.packageName) {
                Log.e(TAG, "Package name mismatch: ${archiveInfo.packageName}")
                return false
            }

            // Check version code is higher (M-02: API 28+ guard)
            val archiveCode = getLongVersionCode(archiveInfo)
            val installedCode = getCurrentVersionCodeLong()
            if (archiveCode <= installedCode) {
                Log.e(TAG, "Archive versionCode $archiveCode not newer than installed $installedCode")
                return false
            }

            // Get archive signing certificate
            val archiveSigner = getArchiveSigner(pm, apkFile.absolutePath)
            if (archiveSigner == null) {
                Log.e(TAG, "Could not extract archive signer")
                return false
            }

            // Compare against pinned certificate (C-03)
            val archiveSignerSha256 = MessageDigest.getInstance("SHA-256").digest(archiveSigner)
            val pinnedBytes = PINNED_SIGNER_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (PINNED_SIGNER_SHA256.isNotEmpty() && !MessageDigest.isEqual(archiveSignerSha256, pinnedBytes)) {
                Log.e(TAG, "APK signer does not match pinned release certificate!")
                return false
            }

            // Compare against currently installed app's signer
            val installedSigner = getInstalledSigner(pm)
            if (installedSigner == null) {
                Log.e(TAG, "Could not extract installed signer")
                return false
            }
            if (!MessageDigest.isEqual(archiveSigner, installedSigner)) {
                Log.e(TAG, "APK signer does not match installed app signer!")
                return false
            }

            Log.i(TAG, "APK signature verified successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "APK signature verification failed")
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageArchiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun getLongVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun getArchiveSigner(pm: PackageManager, apkPath: String): ByteArray? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val info = getPackageArchiveInfo(pm, apkPath, flags.toInt())
                info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                val info = getPackageArchiveInfo(pm, apkPath, PackageManager.GET_SIGNATURES)
                info?.signatures?.firstOrNull()?.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting archive signer")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledSigner(pm: PackageManager): ByteArray? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    pm.getPackageInfo(context.packageName, flags.toInt())
                }
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed signer")
            null
        }
    }

    private fun getCurrentVersionCodeLong(): Long {
        return getLongVersionCode(getCurrentPackageInfo())
    }

    private fun getCurrentPackageInfo(): PackageInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
    }

    fun getCurrentVersionInfo(): Map<String, Any> {
        val packageInfo = getCurrentPackageInfo()

        return mapOf(
            "version" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "longVersionCode" to getLongVersionCode(packageInfo),
            "packageName" to context.packageName,
            "deviceModel" to Build.MODEL,
            "deviceManufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT
        )
    }

    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun close() {
        httpClient.close()
    }
}
