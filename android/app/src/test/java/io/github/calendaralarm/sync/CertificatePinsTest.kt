package io.github.calendaralarm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.test.assertFailsWith

/**
 * Unit tests for CertificatePins (phase 3, point 1 — pin set + SAN matcher).
 *
 * Coverage:
 * - Every pin constant is valid base64 of exactly 32 bytes (SHA-256).
 * - The pin set contains ≥ 3 independent CA families (GTS, ISRG, SSL.com).
 * - hostnameMatches: exact match, wildcard (one label), multi-label wildcard fails,
 *   bare domain with wildcard fails, mismatch fails, case-insensitive, trailing dot.
 * - extractHost from server URLs.
 * - decodePin fails fail-closed on invalid input.
 * - chainMatchesPin with mock certificates (no sun.* internals needed).
 *
 * Runs as pure JVM test (no Robolectric needed).
 */
class CertificatePinsTest {

    // ============== Pin format: every pin = base64 of 32 bytes ==============

    @Test
    fun `all pin constants are valid base64 of 32 bytes`() {
        val allePins = listOf(
            CertificatePins.PIN_GTS_WE1,
            CertificatePins.PIN_GTS_ROOT_R1,
            CertificatePins.PIN_GTS_ROOT_R2,
            CertificatePins.PIN_GTS_ROOT_R3,
            CertificatePins.PIN_GTS_ROOT_R4,
            CertificatePins.PIN_ISRG_ROOT_X1,
            CertificatePins.PIN_ISRG_ROOT_X2,
            CertificatePins.PIN_SSLCOM_ROOT_CA_RSA,
            CertificatePins.PIN_SSLCOM_ROOT_CA_ECC,
            CertificatePins.PIN_SSLCOM_TLS_RSA_ROOT_2022,
            CertificatePins.PIN_SSLCOM_TLS_ECC_ROOT_2022,
            CertificatePins.PIN_GLOBALSIGN_ROOT_CA
        )
        assertTrue("pin set must not be empty", allePins.isNotEmpty())
        for (pin in allePins) {
            val bytes = CertificatePins.decodePin(pin)
            assertEquals("pin $pin must be 32 bytes", 32, bytes.size)
            assertTrue("isValidPin must be true for $pin", CertificatePins.isValidPin(pin))
        }
        // All pins must be unique (otherwise there is a copy error)
        assertEquals("pins must be unique", allePins.size, allePins.toSet().size)
    }

    @Test
    fun `ALL_PINS contains all families together`() {
        val verwacht = CertificatePins.PINS_GTS.size +
            CertificatePins.PINS_ISRG.size +
            CertificatePins.PINS_SSLCOM.size +
            CertificatePins.PINS_GLOBALSIGN.size
        assertEquals(verwacht, CertificatePins.ALL_PINS.size)
    }

    @Test
    fun `pin set contains at least 3 independent CA families (GTS, ISRG, SSLcom)`() {
        assertTrue("GTS family must have ≥ 2 pins", CertificatePins.PINS_GTS.size >= 2)
        assertTrue("ISRG family must have ≥ 1 pin", CertificatePins.PINS_ISRG.size >= 1)
        assertTrue("SSL.com family must have ≥ 2 pins", CertificatePins.PINS_SSLCOM.size >= 2)
        // Independence: no overlap between families
        val gts = CertificatePins.PINS_GTS
        val isrg = CertificatePins.PINS_ISRG
        val sslcom = CertificatePins.PINS_SSLCOM
        assertTrue("GTS ∩ ISRG must be empty", gts.intersect(isrg).isEmpty())
        assertTrue("GTS ∩ SSL.com must be empty", gts.intersect(sslcom).isEmpty())
        assertTrue("ISRG ∩ SSL.com must be empty", isrg.intersect(sslcom).isEmpty())
    }

    @Test
    fun `known verified pin values are exactly as documented`() {
        // These values were verified by PM on 2026-11-22 — a typo here
        // would silently install the wrong pin.
        assertEquals("kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=", CertificatePins.PIN_GTS_WE1)
        assertEquals("mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=", CertificatePins.PIN_GTS_ROOT_R4)
        assertEquals("hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", CertificatePins.PIN_GTS_ROOT_R1)
        assertEquals("Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=", CertificatePins.PIN_GTS_ROOT_R2)
        assertEquals("QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=", CertificatePins.PIN_GTS_ROOT_R3)
        assertEquals("C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", CertificatePins.PIN_ISRG_ROOT_X1)
        assertEquals("diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", CertificatePins.PIN_ISRG_ROOT_X2)
        assertEquals("0cRTd+vc1hjNFlHcLgLCHXUeWqn80bNDH/bs9qMTSPo=", CertificatePins.PIN_SSLCOM_ROOT_CA_RSA)
        assertEquals("oyD01TTXvpfBro3QSZc1vIlcMjrdLTiL/M9mLCPX+Zo=", CertificatePins.PIN_SSLCOM_ROOT_CA_ECC)
        assertEquals("K89VOmb1cJAN3TK6bf4ezAbJGC1mLcG2Dh97dnwr3VQ=", CertificatePins.PIN_SSLCOM_TLS_RSA_ROOT_2022)
        assertEquals("G/ANXI8TwJTdF+AFBM8IiIUPEv0Gf6H5LA/b9guG4yE=", CertificatePins.PIN_SSLCOM_TLS_ECC_ROOT_2022)
        assertEquals("K87oWBWM9UZfyddvDfoxL+8lpNyoUB2ptGtn0fv6G2Q=", CertificatePins.PIN_GLOBALSIGN_ROOT_CA)
    }

    // ============== decodePin fail-closed ==============

    @Test
    fun `decodePin throws on non-base64`() {
        assertFailsWith<IllegalArgumentException> {
            CertificatePins.decodePin("!!!not-base64!!!")
        }
    }

    @Test
    fun `decodePin throws on wrong length (not 32 bytes)`() {
        val teKort = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFailsWith<IllegalArgumentException> {
            CertificatePins.decodePin(teKort)
        }
        assertFalse(CertificatePins.isValidPin(teKort))
    }

    @Test
    fun `isValidPin is false for empty string`() {
        assertFalse(CertificatePins.isValidPin(""))
    }

    // ============== hostnameMatches (own SAN matcher, point 2) ==============

    @Test
    fun `exact match works`() {
        assertTrue(CertificatePins.hostnameMatches("alarm.example.com", "alarm.example.com"))
    }

    @Test
    fun `match is case-insensitive and ignores trailing dot`() {
        assertTrue(CertificatePins.hostnameMatches("Alarm.Example.COM", "alarm.example.com."))
        assertTrue(CertificatePins.hostnameMatches("alarm.example.com.", "ALARM.EXAMPLE.COM"))
    }

    @Test
    fun `wildcard matches one label`() {
        assertTrue(CertificatePins.hostnameMatches("*.example.com", "alarm.example.com"))
        assertTrue(CertificatePins.hostnameMatches("*.example.com", "alarm-api.example.com"))
    }

    @Test
    fun `wildcard does NOT match multi-label host (a b example com)`() {
        assertFalse(CertificatePins.hostnameMatches("*.example.com", "a.b.example.com"))
    }

    @Test
    fun `wildcard does NOT match the bare domain itself`() {
        assertFalse(CertificatePins.hostnameMatches("*.example.com", "example.com"))
    }

    @Test
    fun `wildcard in middle or end of pattern is NOT accepted`() {
        assertFalse(CertificatePins.hostnameMatches("alarm.*.nl", "alarm.example.com"))
        assertFalse(CertificatePins.hostnameMatches("*alarm.example.com", "alarm.example.com"))
        assertFalse(CertificatePins.hostnameMatches("*", "alarm.example.com"))
        assertFalse(CertificatePins.hostnameMatches("*.*.nl", "a.b.nl"))
    }

    @Test
    fun `mismatch fails (fail-closed)`() {
        assertFalse(CertificatePins.hostnameMatches("alarm.example.com", "evil.example.com"))
        assertFalse(CertificatePins.hostnameMatches("other.example.com", "alarm.example.com"))
        // Wildcard of another domain does not match
        assertFalse(CertificatePins.hostnameMatches("*.example.com", "alarm.example.org"))
    }

    @Test
    fun `empty input fails (fail-closed)`() {
        assertFalse(CertificatePins.hostnameMatches("", "alarm.example.com"))
        assertFalse(CertificatePins.hostnameMatches("alarm.example.com", ""))
        assertFalse(CertificatePins.hostnameMatches("*.example.com", ".example.com"))
    }

    @Test
    fun `wildcard ample-com does not match bare example-com`() {
        // "*.ample.com" → suffix ".ample.com"; host "example.com" does not end with ".ample.com"
        assertFalse(CertificatePins.hostnameMatches("*.ample.com", "example.com"))
        assertTrue(CertificatePins.hostnameMatches("*.ample.com", "x.ample.com"))
    }

    // ============== extractHost ==============

    @Test
    fun `extractHost extracts host from server URL`() {
        assertEquals("alarm-api.example.com", CertificatePins.extractHost("https://alarm-api.example.com"))
        assertEquals("alarm-api.example.com", CertificatePins.extractHost("https://alarm-api.example.com/"))
        assertEquals("alarm.example.com", CertificatePins.extractHost("https://alarm.example.com/version.json"))
        assertEquals("alarm.example.com", CertificatePins.extractHost("https://ALARM.EXAMPLE.COM"))
    }

    @Test
    fun `extractHost returns null on invalid URL`() {
        assertEquals(null, CertificatePins.extractHost("not-a-url"))
        assertEquals(null, CertificatePins.extractHost(""))
    }

    // ============== chainMatchesPin with mock certs ==============

    @Test
    fun `chainMatchesPin matches when one cert in chain has the pin`() {
        val cert = mockCert("CN=test-root")
        val pin = Base64.getEncoder().encodeToString(CertificatePins.spkiSha256(cert))
        assertTrue(CertificatePins.chainMatchesPin(listOf(cert), setOf(pin)))
        // Other pin → no match
        val andereCert = mockCert("CN=other-root")
        val anderePin = Base64.getEncoder().encodeToString(CertificatePins.spkiSha256(andereCert))
        assertFalse(CertificatePins.chainMatchesPin(listOf(cert), setOf(anderePin)))
    }

    @Test
    fun `chainMatchesPin also matches when only a root in the chain matches`() {
        val root = mockCert("CN=root")
        val leaf = mockCert("CN=leaf")
        val rootPin = Base64.getEncoder().encodeToString(CertificatePins.spkiSha256(root))
        // Chain [leaf, root] — pin is on root (second element): root-level pinning
        assertTrue(CertificatePins.chainMatchesPin(listOf(leaf, root), setOf(rootPin)))
    }

    @Test
    fun `chainMatchesPin fails on empty chain`() {
        assertFalse(CertificatePins.chainMatchesPin(emptyList()))
    }

    @Test
    fun `chainMatchesPin with random cert does NOT match the default ALL_PINS set`() {
        assertFalse(CertificatePins.chainMatchesPin(listOf(mockCert("CN=random"))))
    }

    // ============== verifyLeafHostname fail-closed (with mock certs) ==============

    @Test
    fun `verifyLeafHostname accepts exact SAN match`() {
        val cert = mockCertWithSan("CN=ignored", listOf("alarm.example.com"))
        CertificatePins.verifyLeafHostname(cert, "alarm.example.com") // does not throw
    }

    @Test
    fun `verifyLeafHostname accepts wildcard SAN for one label`() {
        val cert = mockCertWithSan("CN=ignored", listOf("*.example.com"))
        CertificatePins.verifyLeafHostname(cert, "alarm.example.com")
        CertificatePins.verifyLeafHostname(cert, "alarm-api.example.com")
    }

    @Test
    fun `verifyLeafHostname fails on multi-label under wildcard`() {
        val cert = mockCertWithSan("CN=ignored", listOf("*.example.com"))
        assertFailsWith<java.security.cert.CertificateException> {
            CertificatePins.verifyLeafHostname(cert, "a.b.example.com")
        }
    }

    @Test
    fun `verifyLeafHostname fails on mismatch`() {
        val cert = mockCertWithSan("CN=ignored", listOf("other.example.com"))
        assertFailsWith<java.security.cert.CertificateException> {
            CertificatePins.verifyLeafHostname(cert, "alarm.example.com")
        }
    }

    @Test
    fun `verifyLeafHostname fails fail-closed on no SAN and no CN match`() {
        val cert = mockCertWithSan("CN=ignored", emptyList())
        assertFailsWith<java.security.cert.CertificateException> {
            CertificatePins.verifyLeafHostname(cert, "alarm.example.com")
        }
    }

    @Test
    fun `verifyLeafHostname uses CN as fallback when there is no dNSName SAN`() {
        val cert = mockCertWithSan("CN=alarm.example.com", emptyList())
        CertificatePins.verifyLeafHostname(cert, "alarm.example.com") // does not throw
    }

    @Test
    fun `verifyLeafHostname fails on empty expected host`() {
        val cert = mockCertWithSan("CN=x", listOf("alarm.example.com"))
        assertFailsWith<IllegalArgumentException> {
            CertificatePins.verifyLeafHostname(cert, "")
        }
    }

    // ============== helpers: mock X509Certificate via shared TestCerts ==============

    private fun mockCert(dn: String, sans: List<String>? = null): X509Certificate =
        TestCerts.mockCert(dn, sans)

    private fun mockCertWithSan(cn: String, dnsSans: List<String>): X509Certificate =
        TestCerts.withSans(cn, *dnsSans.toTypedArray())
}
