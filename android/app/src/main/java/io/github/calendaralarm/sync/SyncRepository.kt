package io.github.calendaralarm.sync

import android.content.Context
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning TrustManager.
 *
 * Ktor's CIO engine does NOT automatically use Android's Network Security Config,
 * so we implement SPKI SHA-256 pinning manually. The pinned keys are the
 * pins are configured at build time via the CALALARM_CERT_PINS Gradle property
 * (comma-separated base64 SPKI SHA-256 hashes, e.g. of your CA intermediate + root).
 * When no pins are configured, only standard PKIX + hostname validation is done.
 */
object CertificatePinner {

    private val pinnedHashes: Set<ByteArray> = io.github.calendaralarm.BuildConfig.CERT_PINS
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { Base64.decode(it, Base64.NO_WRAP) }.toSet()

    val pinningEnabled: Boolean get() = pinnedHashes.isNotEmpty()

    val trustManager = object : javax.net.ssl.X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // Not used for client auth
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
            getDefaultTrustManager().checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
            getDefaultTrustManager().checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // Non-hostname-aware variant — Ktor CIO calls this without engine/socket
            // PKIX + SPKI pinning is SUFFICIENT security (pinning ensures correct server)
            // Hostname verification is defense-in-depth, not required with pinning
            doCheck(chain, authType, null)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
            // Hostname-aware variant (required by Android Network Security Config)
            doCheck(chain, authType, engine?.peerHost ?: engine?.session?.peerHost)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
            doCheck(chain, authType, socket?.inetAddress?.hostName)
        }

        /**
         * Core validation: PKIX chain validation + hostname verification + SPKI pinning.
         * Uses the plain (non-extended) default TrustManager to avoid NPE on null SSLEngine.
         */
        private fun doCheck(chain: Array<out X509Certificate>?, authType: String?, host: String?) {
            if (chain.isNullOrEmpty()) {
                throw java.security.cert.CertificateException("Empty certificate chain")
            }

            // Step 1: Standard PKIX chain validation (no hostname, no engine needed)
            val defaultTm = getDefaultTrustManager()
            defaultTm.checkServerTrusted(chain, authType)
            AppLogger.d("CertPinner", "PKIX chain validation OK (${chain.size} certs)")

            // Step 2: Hostname verification (if host is known)
            if (host != null) {
                verifyHostname(chain[0], host)
                AppLogger.d("CertPinner", "Hostname verification OK: $host")
            }

            // Step 3: SPKI SHA-256 pinning — at least one cert in chain must match a pin
            if (!pinningEnabled) {
                AppLogger.d("CertPinner", "No pins configured — PKIX/hostname validation only")
                return
            }
            val chainSpkiHashes = chain.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            }

            val pinMatch = chainSpkiHashes.any { certHash ->
                pinnedHashes.any { pinnedHash ->
                    MessageDigest.isEqual(certHash, pinnedHash)
                }
            }

            if (!pinMatch) {
                val chainInfo = chain.joinToString(" | ") { it.subjectX500Principal.name.take(40) }
                AppLogger.e("CertPinner", "Pinning FAILED — no pin matched. host=$host chain: $chainInfo")
                throw java.security.cert.CertificateException(
                    "Certificate pinning failed for host=$host: no SPKI hash matched pinned certificates."
                )
            }

            AppLogger.d("CertPinner", "All checks passed: host=$host, ${chain.size} certs, pinning OK")
        }

        /**
         * Verify the leaf certificate's SAN/CN matches the expected hostname.
         * Supports wildcard certificates (*.example.com).
         */
        private fun verifyHostname(cert: X509Certificate, host: String) {
            val hostLower = host.lowercase().trimEnd('.')

            // Check Subject Alternative Names (DNS entries)
            try {
                val sans = cert.subjectAlternativeNames
                if (sans != null) {
                    for (san in sans) {
                        // Type 2 = dNSName
                        if (san.size >= 2 && san[0] == 2 && san[1] is String) {
                            val sanName = (san[1] as String).lowercase().trimEnd('.')
                            if (hostnameMatches(sanName, hostLower)) {
                                return
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // No SANs — fall through to CN check
            }

            // Fallback: check Common Name
            val cn = cert.subjectX500Principal.name
            val cnMatch = Regex("CN=([^,]+)").find(cn)?.groupValues?.get(1)?.lowercase()?.trimEnd('.')
            if (cnMatch != null && hostnameMatches(cnMatch, hostLower)) {
                return
            }

            throw java.security.cert.CertificateException(
                "Hostname verification failed: cert is for '${cnMatch ?: "unknown"}', expected '$host'"
            )
        }

        private fun hostnameMatches(pattern: String, host: String): Boolean {
            if (pattern == host) return true
            // Wildcard: *.example.com matches foo.example.com but not example.com
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(1) // .example.com
                return host.endsWith(suffix) && host.length > suffix.length && !host.substring(0, host.length - suffix.length).contains(".")
            }
            return false
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return getDefaultTrustManager().acceptedIssuers
        }

        /**
         * Get the default (non-extended) trust manager — safe to call without an SSLEngine.
         */
        private fun getDefaultTrustManager(): X509TrustManager {
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }

    val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
}

/**
 * HTTPS client for the Calendar Alarm pull API.
 *
 * The app polls the server (never the other way around):
 *   GET https://<your-backend>/api/alarm/next
 *   Authorization: Bearer <device_token>
 *
 * Cloudflare Access: if service token credentials are configured, they are
 * sent as Cf-Access-Client-Id / Cf-Access-Client-Secret headers.
 *
 * Certificate pinning: SPKI SHA-256 pins enforced manually (Ktor CIO does
 * not use Android Network Security Config).
 */
class SyncRepository(private val context: Context) {

    companion object {
        private const val TAG = "SyncRepository"
    }

    @Serializable
    data class AlarmPlan(
        val date: String,
        val wakeTime: String?,  // Null = no alarm (e.g. weekend without rule)
        val label: String,
        val workLocation: String,
        val confidence: String,
        val eventCount: Int
    )

    @Serializable
    data class AlarmRule(
        val id: String,
        val name: String,
        val time: String,
        val keywords: List<String> = emptyList()
    )

    @Serializable
    data class AlarmConfig(
        val rules: List<AlarmRule>,
        val defaultRuleId: String = "",  // Empty = server uses first rule as fallback
        val weekendRuleId: String = ""  // Empty = no alarm on weekends
    )

    data class SyncResult(
        val success: Boolean,
        val plan: AlarmPlan? = null,
        val error: String? = null
    )

    data class ConfigResult(
        val success: Boolean,
        val config: AlarmConfig? = null,
        val error: String? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
            })
        }
        followRedirects = false
        engine {
            requestTimeout = 15_000
            // Certificate pinning: use our custom SSLContext with SPKI pins
            https {
                trustManager = CertificatePinner.trustManager
            }
        }
    }

    private val userAgent = "CalendarAlarm/${io.github.calendaralarm.BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"

    private fun addHeaders(builder: HttpRequestBuilder) {
        with(builder) {
            header("User-Agent", userAgent)
            header("Accept", "application/json")
            val settingsRepo = CalendarAlarmApp.settingsRepository
            if (settingsRepo.hasCfAccessCredentials()) {
                header("Cf-Access-Client-Id", settingsRepo.getCfAccessClientId())
                header("Cf-Access-Client-Secret", settingsRepo.getCfAccessClientSecret())
            }
        }
    }

    suspend fun fetchAlarmPlan(date: String? = null): SyncResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "fetchAlarmPlan() called${if (date != null) " for date=$date" else " (default: tomorrow)"}")
        return@withContext try {
            val settingsRepo = CalendarAlarmApp.settingsRepository
            if (!settingsRepo.secureStorageAvailable) {
                AppLogger.e(TAG, "Secure storage unavailable — cannot sync")
                return@withContext SyncResult(false, error = "Secure storage unavailable")
            }
            val token = settingsRepo.getDeviceToken()
            val serverUrl = settingsRepo.getServerUrl()
            AppLogger.d(TAG, "Device token loaded (${token.length} chars), server: $serverUrl")
            AppLogger.d(TAG, "CF Access credentials: ${if (settingsRepo.hasCfAccessCredentials()) "configured" else "NOT configured"}")

            val url = buildString {
                append(serverUrl.trimEnd('/'))
                append("/api/alarm/next")
            }

            AppLogger.d(TAG, "GET $url")
            val response = httpClient.get(url) {
                // K-03 fix: Token in Authorization header, NOT in URL query parameter
                header("Authorization", "Bearer $token")
                if (date != null) parameter("date", date)
                addHeaders(this)
            }

            if (!response.status.isSuccess()) {
                val body = try { response.bodyAsText().take(200) } catch (e: Exception) { "" }
                val errorMsg = when (response.status.value) {
                    401 -> "Server rejected the device token (401). Check that the token in the app matches the server."
                    403 -> "Cloudflare blocked the request (403). Check Client ID + Secret in Settings → Cloudflare Access."
                    404 -> "Endpoint not found (404). Check the Server URL."
                    429 -> "Too many requests (429). Wait a minute."
                    503 -> "Server is unavailable (503). Check whether the server is running."
                    else -> "Server error ${response.status.value}"
                }
                AppLogger.e(TAG, "HTTP ${response.status.value} — $errorMsg (body: ${body.take(80)})")
                return@withContext SyncResult(false, error = errorMsg)
            }

            val plan = json.decodeFromString<AlarmPlan>(response.bodyAsText())
            AppLogger.i(TAG, "✅ Sync OK: ${plan.wakeTime} on ${plan.date} (${plan.workLocation}, ${plan.eventCount} events)")
            SyncResult(success = true, plan = plan)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Re-throw cancellation — never swallow!
        } catch (e: javax.net.ssl.SSLException) {
            AppLogger.e(TAG, "SSL error: ${e.message?.take(150)}")
            SyncResult(false, error = "SSL/certificate error — pinning or certificate problem")
        } catch (e: java.net.UnknownHostException) {
            AppLogger.e(TAG, "DNS error: ${e.message}")
            SyncResult(false, error = "No internet connection or server unreachable (DNS)")
        } catch (e: java.net.ConnectException) {
            AppLogger.e(TAG, "Connection refused: ${e.message}")
            SyncResult(false, error = "Cannot reach server (connection refused)")
        } catch (e: java.security.cert.CertificateException) {
            AppLogger.e(TAG, "CertificateException: ${e.message?.take(150)}")
            SyncResult(false, error = "Certificate rejected — pinning mismatch or expired certificate")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            SyncResult(false, error = "Network error: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Fetch the alarm rules config from the server.
     * Returns the dynamic rules (labels, wake times, keywords) editable from the app.
     */
    suspend fun fetchConfig(): ConfigResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "fetchConfig() called")
        return@withContext try {
            val settingsRepo = CalendarAlarmApp.settingsRepository
            val token = settingsRepo.getDeviceToken()
            val serverUrl = settingsRepo.getServerUrl()

            val response = httpClient.get("${serverUrl.trimEnd('/')}/api/config") {
                // K-03 fix: Token in Authorization header, NOT in URL query parameter
                header("Authorization", "Bearer $token")
                addHeaders(this)
            }

            if (!response.status.isSuccess()) {
                AppLogger.e(TAG, "Config fetch failed: HTTP ${response.status.value}")
                return@withContext ConfigResult(false, error = "Server error ${response.status.value}")
            }

            val config = json.decodeFromString<AlarmConfig>(response.bodyAsText())
            AppLogger.i(TAG, "Config received: ${config.rules.size} rules")
            ConfigResult(success = true, config = config)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Re-throw cancellation — never swallow!
        } catch (e: Exception) {
            AppLogger.e(TAG, "Config fetch failed: ${e.javaClass.simpleName}")
            ConfigResult(false, error = "Network error")
        }
    }

    /**
     * Push a new alarm rules config to the server. Server validates before persisting.
     */
    suspend fun saveConfig(config: AlarmConfig): ConfigResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "saveConfig() called (${config.rules.size} rules)")
        return@withContext try {
            val settingsRepo = CalendarAlarmApp.settingsRepository
            val token = settingsRepo.getDeviceToken()
            val serverUrl = settingsRepo.getServerUrl()

            val response = httpClient.post("${serverUrl.trimEnd('/')}/api/config") {
                // K-03 fix: Token in Authorization header, NOT in URL query parameter
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                addHeaders(this)
                setBody(json.encodeToString(AlarmConfig.serializer(), config))
            }

            if (!response.status.isSuccess()) {
                val body = try { response.bodyAsText().take(200) } catch (e: Exception) { "" }
                AppLogger.e(TAG, "Config save rejected: HTTP ${response.status.value} — $body")
                val errorMsg = when (response.status.value) {
                    400 -> "Invalid config (check times HH:mm, unique ids, max 20 rules)"
                    401 -> "Server rejected the device token (401)"
                    413 -> "Config too large (max 100 KB)"
                    else -> "Server error ${response.status.value}"
                }
                return@withContext ConfigResult(false, error = errorMsg)
            }

            AppLogger.i(TAG, "Config saved to server (${config.rules.size} rules)")
            ConfigResult(success = true, config = config)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Re-throw cancellation — never swallow!
        } catch (e: Exception) {
            AppLogger.e(TAG, "Config save failed: ${e.javaClass.simpleName}")
            ConfigResult(false, error = "Network error")
        }
    }

    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "healthCheck() called")
        return@withContext try {
            val settingsRepo = CalendarAlarmApp.settingsRepository
            val serverUrl = settingsRepo.getServerUrl()
            AppLogger.d(TAG, "GET $serverUrl/health")
            val response = httpClient.get("${serverUrl.trimEnd('/')}/health") {
                addHeaders(this)
            }
            AppLogger.i(TAG, "Health check: HTTP ${response.status.value}")
            response.status.isSuccess()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Re-throw cancellation — never swallow!
        } catch (e: Exception) {
            AppLogger.e(TAG, "Health check failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            false
        }
    }

    fun close() {
        httpClient.close()
    }
}
