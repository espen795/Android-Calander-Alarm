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
 * Pin mismatch — thrown by the pinned trust manager when no SPKI in the chain matches.
 * Subclass of CertificateException so UpdateFallbackPolicy can distinguish it
 * from a hostname failure (no fallback on hostname failure).
 */
class PinMismatchException(message: String) : java.security.cert.CertificateException(message)

/**
 * Hostname mismatch — thrown by the trust manager when the leaf is not for the fixed host.
 * NO fallback trigger (a cert for another host is not a CA rotation).
 */
class HostnameMismatchException(message: String) : java.security.cert.CertificateException(message)

/**
 * Certificate pinning TrustManager — as a FACTORY per fixed host.
 *
 * Ktor's CIO engine does NOT automatically use Android's Network Security Config,
 * so we implement SPKI SHA-256 pinning manually.
 *
 * Phase 3 point 1 (hardening plan 2026-11-22, review #6/#7): the pin set lives in
 * [CertificatePins] and pins root level of ALL public CAs that Cloudflare
 * Universal SSL for example.com can use (GTS, ISRG/Let's Encrypt, SSL.com,
 * GlobalSign as short-term backup).
 *
 * Opus review B1 (2026-11-22): NO global `expectedHost` anymore — that caused a race
 * between concurrent workers (sync = alarm-api.example.com, update =
 * alarm.example.com). Each client now gets its OWN trust-manager instance with
 * a FIXED host: [pinnedTrustManager] / [systemTrustManager]. In the 3-arg
 * overloads a present peerHost must EQUAL the fixed host, otherwise
 * CertificateException. Empty/invalid host → IllegalArgumentException (do not
 * build a client, fail-closed).
 */
object CertificatePinner {

    // SPKI SHA-256 pins (base64) — see CertificatePins for CA + expiry date per pin.
    private val pinnedHashes: Set<ByteArray> =
        CertificatePins.ACTIVE_PINS.map { CertificatePins.decodePin(it) }.toSet()

    /** Normalize a host; empty/invalid host → IllegalArgumentException (fail-closed). */
    private fun normalizeHost(host: String?): String {
        val h = host?.lowercase()?.trim()?.trimEnd('.')
        require(!h.isNullOrEmpty()) {
            "CertificatePinner: empty/invalid host — do not build trust manager/client (fail-closed)"
        }
        return h
    }

    /** Standard PKIX validation against the system trust store. */
    private fun defaultPkixCheck(chain: Array<out X509Certificate>, authType: String?) {
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(null as java.security.KeyStore?)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        tm.checkServerTrusted(chain, authType)
    }

    /**
     * Create a PINNING trust manager for one FIXED host.
     *
     * - 2-arg checkServerTrusted (Ktor CIO, no engine/socket): checks against the fixed host.
     * - 3-arg overloads: a present peerHost must equal the fixed host,
     *   otherwise CertificateException (fail-closed).
     * - Always: PKIX + hostname verification (fail-closed on missing host) + SPKI pinning.
     *
     * @param host fixed host (e.g. from the server URL); empty → IllegalArgumentException
     * @param pins pin set (default: [CertificatePins.ACTIVE_PINS])
     * @param pkixChecker PKIX validation (default: system trust store; tests: no-op)
     */
    fun pinnedTrustManager(
        host: String,
        pins: Set<String> = CertificatePins.ACTIVE_PINS,
        pkixChecker: (Array<out X509Certificate>, String?) -> Unit = ::defaultPkixCheck
    ): javax.net.ssl.X509ExtendedTrustManager {
        val fixedHost = normalizeHost(host)
        val pinBytes = pins.map { CertificatePins.decodePin(it) }.toSet()
        return object : javax.net.ssl.X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
                defaultPkixCheck(chain ?: emptyArray(), authType)
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
                defaultPkixCheck(chain ?: emptyArray(), authType)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Ktor CIO: no engine/socket → fixed host
                doCheck(chain, authType, fixedHost, null)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
                doCheck(chain, authType, fixedHost, engine?.peerHost ?: engine?.session?.peerHost)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
                doCheck(chain, authType, fixedHost, socket?.inetAddress?.hostName)
            }
            private fun doCheck(chain: Array<out X509Certificate>?, authType: String?, host: String, peerHost: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Empty certificate chain")
                // peerHost (if present) must equal the fixed host — fail-closed
                val ph = peerHost?.lowercase()?.trim()?.trimEnd('.')
                if (!ph.isNullOrEmpty() && ph != host) {
                    throw HostnameMismatchException(
                        "peerHost '$ph' != fixed host '$host' — refused (fail-closed)"
                    )
                }
                // Step 1: PKIX chain validation (system trust store)
                pkixChecker(chain, authType)
                AppLogger.d("CertPinner", "PKIX chain validation OK (${chain.size} certs)")
                // Step 2: Hostname verification — ALWAYS (phase 3 point 2, #8; root pins of
                // public CAs without hostname check = any cert from those CAs accepted)
                try {
                    CertificatePins.verifyLeafHostname(chain[0], host)
                } catch (e: java.security.cert.CertificateException) {
                    throw HostnameMismatchException("Hostname verification failed for '$host': ${e.message}")
                }
                AppLogger.d("CertPinner", "Hostname verification OK: $host")
                // Step 3: SPKI pinning — at least one cert in the chain must match
                val pinMatch = chain.any { cert ->
                    val hash = CertificatePins.spkiSha256(cert)
                    pinBytes.any { MessageDigest.isEqual(hash, it) }
                }
                if (!pinMatch) {
                    val chainInfo = chain.joinToString(" | ") { it.subjectX500Principal.name.take(40) }
                    AppLogger.e("CertPinner", "Pinning FAILED — no pin matched. host=$host chain: $chainInfo")
                    throw PinMismatchException(
                        "Certificate pinning failed for host=$host: no SPKI hash matched pinned certificates."
                    )
                }
                AppLogger.d("CertPinner", "All checks passed: host=$host, ${chain.size} certs, pinning OK")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(null as java.security.KeyStore?)
                return tmf.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
            }
        }
    }

    /**
     * SYSTEM-TRUST trust manager for one FIXED host — ONLY for the update-recovery path
     * (phase 3 point 3, #7c). PKIX + hostname verification, no pinning. Sync and log upload
     * NEVER use this variant.
     */
    fun systemTrustManager(
        host: String,
        pkixChecker: (Array<out X509Certificate>, String?) -> Unit = ::defaultPkixCheck
    ): javax.net.ssl.X509ExtendedTrustManager {
        val fixedHost = normalizeHost(host)
        return object : javax.net.ssl.X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
                defaultPkixCheck(chain ?: emptyArray(), authType)
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
                defaultPkixCheck(chain ?: emptyArray(), authType)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                doCheck(chain, authType, fixedHost, null)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {
                doCheck(chain, authType, fixedHost, engine?.peerHost ?: engine?.session?.peerHost)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {
                doCheck(chain, authType, fixedHost, socket?.inetAddress?.hostName)
            }
            private fun doCheck(chain: Array<out X509Certificate>?, authType: String?, host: String, peerHost: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Empty certificate chain")
                val ph = peerHost?.lowercase()?.trim()?.trimEnd('.')
                if (!ph.isNullOrEmpty() && ph != host) {
                    throw HostnameMismatchException("peerHost '$ph' != fixed host '$host' — refused (fail-closed)")
                }
                pkixChecker(chain, authType) // PKIX, system trust store — no pinning
                try {
                    CertificatePins.verifyLeafHostname(chain[0], host)
                } catch (e: java.security.cert.CertificateException) {
                    throw HostnameMismatchException("Hostname verification failed for '$host': ${e.message}")
                }
                AppLogger.w("CertPinner", "SYSTEM-TRUST fallback active (NO pinning) for host=$host — update channel only!")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(null as java.security.KeyStore?)
                return tmf.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
            }
        }
    }

    /** SSLContext for a pinned trust manager (useful if an engine wants an SSLContext). */
    fun pinnedSslContext(host: String): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(pinnedTrustManager(host)), null)
    }

    /** SSLContext for a system-trust manager (update fallback only). */
    fun systemSslContext(host: String): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(systemTrustManager(host)), null)
    }
}

/**
 * HTTPS client for the Calendar Alarm pull API.
 *
 * The app polls the server (never the other way around):
 *   GET https://alarm-api.example.com/api/alarm/next
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
        val wakeTime: String?,  // Null = no alarm (e.g. weekend without a rule)
        val label: String,
        val workLocation: String,
        val confidence: String,
        val eventCount: Int,
        // P2A #13: optional "source": "calendar" | "cache" (server B2 fail-safe).
        // Backwards compatible — old servers do not send it; ignoreUnknownKeys is on.
        // Behavior does NOT change; purely informational/loggable.
        val source: String? = null
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
        val defaultRuleId: String = "",  // Empty = server uses the first rule as fallback
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

    /**
     * Opus review B1: NO global expectedHost anymore — the client is built per HOST
     * with its own pinned trust manager (fixed host). If the server URL
     * changes, a new client is built and the old one closed.
     */
    @Volatile
    private var cachedClientHost: String? = null

    @Volatile
    private var httpClient: HttpClient? = null

    /**
     * HttpClient for the given server URL. Throws IllegalArgumentException if the
     * URL has no valid host (fail-closed — no client without a fixed host).
     * Cached per host: when the server URL changes, the old client is closed.
     */
    @Synchronized
    private fun clientFor(serverUrl: String): HttpClient {
        val host = CertificatePins.extractHost(serverUrl)
            ?: throw IllegalArgumentException("Invalid server URL (no host): $serverUrl — request refused (fail-closed)")
        val current = httpClient
        if (current != null && cachedClientHost == host) return current
        // server URL changed → close old client, build new one
        current?.close()
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
                })
            }
            followRedirects = false
            engine {
                requestTimeout = 15_000
                // Pinning + hostname verification with the FIXED host from the server URL (B1)
                https {
                    trustManager = CertificatePinner.pinnedTrustManager(host)
                }
            }
        }
        httpClient = client
        cachedClientHost = host
        return client
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

    /**
     * Fail-closed: server URL without host → IllegalArgumentException (no client).
     */
    private fun serverUrlOrThrow(): String {
        val serverUrl = CalendarAlarmApp.settingsRepository.getServerUrl()
        CertificatePins.extractHost(serverUrl)
            ?: throw IllegalArgumentException("Invalid server URL (no host): $serverUrl")
        return serverUrl
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
            val serverUrl = serverUrlOrThrow() // B1: fail-closed on URL without host
            val client = clientFor(serverUrl)    // B1: client per host (pinned TM with fixed host)
            AppLogger.d(TAG, "Device token loaded (${token.length} chars), server: $serverUrl")
            AppLogger.d(TAG, "CF Access credentials: ${if (settingsRepo.hasCfAccessCredentials()) "configured" else "NOT configured"}")

            val url = buildString {
                append(serverUrl.trimEnd('/'))
                append("/api/alarm/next")
            }

            AppLogger.d(TAG, "GET $url")
            val response = client.get(url) {
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
                    503 -> "Server unavailable (503). Check that the server is running."
                    else -> "Server error ${response.status.value}"
                }
                AppLogger.e(TAG, "HTTP ${response.status.value} — $errorMsg (body: ${body.take(80)})")
                return@withContext SyncResult(false, error = errorMsg)
            }

            val plan = json.decodeFromString<AlarmPlan>(response.bodyAsText())
            AppLogger.i(TAG, "✅ Sync OK: ${plan.wakeTime} on ${plan.date} (${plan.workLocation}, ${plan.eventCount} events)")
            if (plan.source != null) AppLogger.d(TAG, "Plan source: ${plan.source}")
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
            val serverUrl = serverUrlOrThrow()
            val client = clientFor(serverUrl)

            val response = client.get("${serverUrl.trimEnd('/')}/api/config") {
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
            val serverUrl = serverUrlOrThrow()
            val client = clientFor(serverUrl)

            val response = client.post("${serverUrl.trimEnd('/')}/api/config") {
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
            val serverUrl = serverUrlOrThrow()
            val client = clientFor(serverUrl)
            AppLogger.d(TAG, "GET $serverUrl/health")
            val response = client.get("${serverUrl.trimEnd('/')}/health") {
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
        httpClient?.close()
        httpClient = null
        cachedClientHost = null
    }
}
