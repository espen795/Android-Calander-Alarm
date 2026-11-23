package io.github.calendaralarm.sync

import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * SPKI SHA-256 certificate pins (base64) for all public CAs that Cloudflare
 * Universal SSL for example.com can use.
 *
 * Hardening plan 2026-11-22, phase 3, point 1 (review #6/#7): the old pinset
 * pinned ONLY GTS WE1 + GTS Root R4 + GlobalSign Root CA. On a CA change by
 * Cloudflare (e.g. to Let's Encrypt or SSL.com), sync, log upload and
 * self-update would then all fail at once. Therefore we now pin at root level
 * of ALL relevant public CA families (GTS, ISRG, SSL.com) plus GlobalSign as
 * a short-term backup.
 *
 * Values verified by PM on 2026-11-22, computed from /etc/ssl/certs and the
 * live chain of alarm.example.com. Reproducible with:
 *   projects/smart-alarm-app/scripts/compute_pins.sh
 *
 * NB: the leaf (example.com) is NOT pinned — it rotates with every
 * certificate renewal. Only intermediates/roots are pinned.
 *
 * Pinning at root level is sufficient: any chain ending at one of these roots
 * matches. Hostname verification (see CertificatePinner) is ESSENTIAL because
 * these roots are public CAs — without a hostname check, any certificate from
 * those CAs would be accepted.
 */
object CertificatePins {

    // ===================== Google Trust Services (GTS) =====================
    // Current chain of example.com (Cloudflare Universal SSL → GTS).

    /** GTS WE1 (intermediate, current chain) — rotates, but is an extra pin. */
    const val PIN_GTS_WE1 = "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4="

    /** GTS Root R1 — expires 2036-06-22. */
    const val PIN_GTS_ROOT_R1 = "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc="

    /** GTS Root R2 — expires 2036-06-22. */
    const val PIN_GTS_ROOT_R2 = "Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78="

    /** GTS Root R3 — expires 2036-06-22. */
    const val PIN_GTS_ROOT_R3 = "QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g="

    /** GTS Root R4 — expires 2036-06-22. */
    const val PIN_GTS_ROOT_R4 = "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c="

    val PINS_GTS: Set<String> = setOf(
        PIN_GTS_WE1,
        PIN_GTS_ROOT_R1,
        PIN_GTS_ROOT_R2,
        PIN_GTS_ROOT_R3,
        PIN_GTS_ROOT_R4
    )

    // ===================== Let's Encrypt (ISRG) =====================
    // Fallback if Cloudflare switches to Let's Encrypt.

    /** ISRG Root X1 — expires 2035-09-15. */
    const val PIN_ISRG_ROOT_X1 = "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="

    /** ISRG Root X2 — expires 2040-09-15. */
    const val PIN_ISRG_ROOT_X2 = "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="

    val PINS_ISRG: Set<String> = setOf(
        PIN_ISRG_ROOT_X1,
        PIN_ISRG_ROOT_X2
    )

    // ===================== SSL.com =====================
    // Fallback if Cloudflare switches to SSL.com.

    /** SSL.com Root CA RSA — expires 2041-12-31. */
    const val PIN_SSLCOM_ROOT_CA_RSA = "0cRTd+vc1hjNFlHcLgLCHXUeWqn80bNDH/bs9qMTSPo="

    /** SSL.com Root CA ECC — expires 2041-12-31. */
    const val PIN_SSLCOM_ROOT_CA_ECC = "oyD01TTXvpfBro3QSZc1vIlcMjrdLTiL/M9mLCPX+Zo="

    /** SSL.com TLS RSA Root 2022 — expires 2046-12-31. */
    const val PIN_SSLCOM_TLS_RSA_ROOT_2022 = "K89VOmb1cJAN3TK6bf4ezAbJGC1mLcG2Dh97dnwr3VQ="

    /** SSL.com TLS ECC Root 2022 — expires 2046-12-31. */
    const val PIN_SSLCOM_TLS_ECC_ROOT_2022 = "G/ANXI8TwJTdF+AFBM8IiIUPEv0Gf6H5LA/b9guG4yE="

    val PINS_SSLCOM: Set<String> = setOf(
        PIN_SSLCOM_ROOT_CA_RSA,
        PIN_SSLCOM_ROOT_CA_ECC,
        PIN_SSLCOM_TLS_RSA_ROOT_2022,
        PIN_SSLCOM_TLS_ECC_ROOT_2022
    )

    // ===================== GlobalSign =====================

    /** GlobalSign Root CA — expires 2028-01-28 (short-term backup, NOT long-term). */
    const val PIN_GLOBALSIGN_ROOT_CA = "K87oWBWM9UZfyddvDfoxL+8lpNyoUB2ptGtn0fv6G2Q="

    val PINS_GLOBALSIGN: Set<String> = setOf(PIN_GLOBALSIGN_ROOT_CA)

    /**
     * Full pinset: all pins from all CA families.
     * At least one cert in the server chain must match (root level is sufficient).
     */
    val ALL_PINS: Set<String> =
        PINS_GTS + PINS_ISRG + PINS_SSLCOM + PINS_GLOBALSIGN

    /**
     * Pins actually enforced at runtime. Build-time override via the
     * CALALARM_CERT_PINS Gradle property (comma-separated base64 SPKI SHA-256,
     * e.g. for a private CA); empty = the public-CA root set [ALL_PINS], which
     * covers Let's Encrypt, Google Trust Services, SSL.com and GlobalSign.
     */
    val ACTIVE_PINS: Set<String> by lazy {
        val custom = io.github.calendaralarm.BuildConfig.CERT_PINS
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (custom.isEmpty()) ALL_PINS else custom
    }

    /**
     * Decode a base64 SPKI SHA-256 pin to 32 bytes.
     * @throws IllegalArgumentException if the pin is not valid base64 of 32 bytes.
     */
    fun decodePin(pin: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(pin)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Pin is not valid base64: ${pin.take(16)}…", e)
        }
        require(bytes.size == 32) {
            "Pin must be SHA-256 (32 bytes), but is ${bytes.size} bytes: ${pin.take(16)}…"
        }
        return bytes
    }

    /** True if the pin is valid base64 of exactly 32 bytes. */
    fun isValidPin(pin: String): Boolean = try {
        decodePin(pin).size == 32
    } catch (e: IllegalArgumentException) {
        false
    }

    /** SPKI SHA-256 hash of a certificate (the bytes that get pinned). */
    fun spkiSha256(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    /** True if at least one cert in the chain matches the pinset. */
    fun chainMatchesPin(chain: List<X509Certificate>, pins: Set<String> = ALL_PINS): Boolean {
        val pinned = pins.map { decodePin(it) }
        return chain.any { cert ->
            val hash = spkiSha256(cert)
            pinned.any { MessageDigest.isEqual(hash, it) }
        }
    }

    /**
     * Extract the host from a server URL (e.g. "https://alarm-api.example.com" →
     * "alarm-api.example.com"). Returns null on invalid URL.
     */
    fun extractHost(serverUrl: String): String? = try {
        URI(serverUrl.trim()).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    // ===================== Hostname (SAN) matching =====================
    // Own matcher (no org.apache.http.conn.ssl, no sun-internal classes).
    // Fail-closed: only exact match or a single wildcard label ("*.example.com").

    /**
     * Match a certificate pattern (SAN dNSName or CN) against a host.
     *
     * Rules:
     * - Case-insensitive; trailing dot is ignored.
     * - Exact match: "alarm.example.com" == "alarm.example.com".
     * - Wildcard ONLY as the single leftmost label: "*.example.com" matches
     *   "alarm.example.com" but NOT "a.b.example.com" and NOT "example.com".
     * - Anything else → false (fail-closed).
     */
    fun hostnameMatches(pattern: String, host: String): Boolean {
        val p = pattern.lowercase().trim().trimEnd('.')
        val h = host.lowercase().trim().trimEnd('.')
        if (p.isEmpty() || h.isEmpty()) return false
        if (p == h) return true
        if (p.startsWith("*.")) {
            val suffix = p.substring(1) // ".example.com"
            if (!h.endsWith(suffix)) return false
            val prefix = h.substring(0, h.length - suffix.length)
            // Exactly one label: no dot in prefix, and not empty
            return prefix.isNotEmpty() && !prefix.contains('.') && !prefix.contains('*')
        }
        return false
    }

    /**
     * Verify that the leaf certificate has a SAN (dNSName, type 2) or CN that
     * matches the expected host. Throws CertificateException on mismatch
     * (fail-closed: no SAN and no CN → fail).
     */
    fun verifyLeafHostname(cert: X509Certificate, expectedHost: String) {
        val hostLower = expectedHost.lowercase().trim().trimEnd('.')
        require(hostLower.isNotEmpty()) { "Empty expected host — fail-closed" }

        // 1) Subject Alternative Names (type 2 = dNSName)
        try {
            val sans = cert.subjectAlternativeNames
            if (sans != null) {
                for (san in sans) {
                    if (san.size >= 2 && san[0] == 2 && san[1] is String) {
                        if (hostnameMatches(san[1] as String, hostLower)) return
                    }
                }
            }
        } catch (e: java.security.cert.CertificateParsingException) {
            // continue to CN fallback
        }

        // 2) Fallback: Common Name (only when there are no dNSName SANs —
        //    modern clients don't trust CN when a SAN is present, but for
        //    compatibility with old certs we accept CN when there is NO SAN)
        val hasDnsSans = try {
            cert.subjectAlternativeNames?.any { it.size >= 2 && it[0] == 2 } == true
        } catch (e: Exception) {
            false
        }
        if (!hasDnsSans) {
            val cn = cert.subjectX500Principal.name
            val cnMatch = Regex("CN=([^,]+)").find(cn)?.groupValues?.get(1)
            if (cnMatch != null && hostnameMatches(cnMatch, hostLower)) return
        }

        throw java.security.cert.CertificateException(
            "Hostname verification failed: cert is not for '$hostLower' (SANs/CN mismatch)"
        )
    }
}
