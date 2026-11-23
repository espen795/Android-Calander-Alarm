package io.github.calendaralarm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.X509Certificate
import kotlin.test.assertFailsWith

/**
 * Unit tests on the REAL trust managers per host (Opus B2a).
 *
 * Coverage (every check fails closed):
 * - host mismatch (leaf for a different host than the pinned one) → HostnameMismatchException
 * - pin mismatch (valid host, no pin in chain) → PinMismatchException
 * - valid chain + host + pin → OK (2-arg and 3-arg)
 * - peerHost ≠ vaste host in 3-arg overload → HostnameMismatchException
 * - system variant: does hostname check but NO pinning (pin mismatch → OK)
 * - empty/invalid host → IllegalArgumentException (no manager)
 * - empty chain → CertificateException
 *
 * The pkixChecker is a no-op in tests (we test our own checks, not the JVM PKIX).
 *
 * Runs under Robolectric so AppLogger (android.util.Log) works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CertificatePinnerHostTest {

    private val noPkix: (Array<out X509Certificate>, String?) -> Unit = { _, _ -> }

    private fun chain(vararg certs: X509Certificate): Array<X509Certificate> = arrayOf(*certs)

    // ============== Valid chain + host + pin → OK ==============

    @Test
    fun `valid chain with pin and correct host passes 2-arg check`() {
        val root = TestCerts.mockCert("CN=GTS Root R4")
        val leaf = TestCerts.withSans("CN=alarm", "alarm-api.example.com")
        val pins = setOf(TestCerts.pinOf(root))
        val tm = CertificatePinner.pinnedTrustManager("alarm-api.example.com", pins, noPkix)
        tm.checkServerTrusted(chain(leaf, root), "RSA") // does not throw
    }

    @Test
    fun `valid chain passes 3-arg check with equal peerHost`() {
        val root = TestCerts.mockCert("CN=root")
        val leaf = TestCerts.withSans("CN=alarm", "alarm.example.com")
        val pins = setOf(TestCerts.pinOf(root))
        val tm = CertificatePinner.pinnedTrustManager("alarm.example.com", pins, noPkix)
        val engine = FakeEngine(peerHost = "alarm.example.com")
        tm.checkServerTrusted(chain(leaf, root), "RSA", engine) // does not throw
    }

    // ============== host mismatch → exception ==============

    @Test
    fun `host mismatch in 2-arg check throws HostnameMismatchException`() {
        val root = TestCerts.mockCert("CN=root")
        val leaf = TestCerts.withSans("CN=evil", "evil.example.com") // cert for a DIFFERENT host
        val pins = setOf(TestCerts.pinOf(root))
        val tm = CertificatePinner.pinnedTrustManager("alarm-api.example.com", pins, noPkix)
        val e = assertFailsWith<HostnameMismatchException> {
            tm.checkServerTrusted(chain(leaf, root), "RSA")
        }
        assertTrue(e.message!!.contains("alarm-api.example.com"))
    }

    @Test
    fun `wildcard SAN matcht vaste host`() {
        val root = TestCerts.mockCert("CN=root")
        val leaf = TestCerts.withSans("CN=wild", "*.example.com")
        val pins = setOf(TestCerts.pinOf(root))
        val tm = CertificatePinner.pinnedTrustManager("alarm.example.com", pins, noPkix)
        tm.checkServerTrusted(chain(leaf, root), "RSA") // does not throw
    }

    @Test
    fun `wildcard SAN does NOT match multi-label pinned host`() {
        val root = TestCerts.mockCert("CN=root")
        val leaf = TestCerts.withSans("CN=wild", "*.example.com")
        val pins = setOf(TestCerts.pinOf(root))
        val tm = CertificatePinner.pinnedTrustManager("a.b.example.com", pins, noPkix)
        assertFailsWith<HostnameMismatchException> {
            tm.checkServerTrusted(chain(leaf, root), "RSA")
        }
    }

    // ============== pin mismatch → PinMismatchException ==============

    @Test
    fun `pin mismatch throws PinMismatchException (host OK, no pin in chain)`() {
        val root = TestCerts.mockCert("CN=unpinned-root")
        val leaf = TestCerts.withSans("CN=alarm", "alarm-api.example.com")
        // pin set contains a DIFFERENT root than in the chain
        val otherRoot = TestCerts.mockCert("CN=other-root")
        val pins = setOf(TestCerts.pinOf(otherRoot))
        val tm = CertificatePinner.pinnedTrustManager("alarm-api.example.com", pins, noPkix)
        val e = assertFailsWith<PinMismatchException> {
            tm.checkServerTrusted(chain(leaf, root), "RSA")
        }
        assertTrue(e.message!!.contains("pinning failed", ignoreCase = true))
    }

    // ============== peerHost ≠ pinned host → exception (3-arg) ==============

    @Test
    fun `3-arg check with peerHost unequal to pinned host throws`() {
        val root = TestCerts.mockCert("CN=root")
        val leaf = TestCerts.withSans("CN=alarm", "alarm.example.com")
        val pins = setOf(TestCerts.pinOf(root))
        val tm = CertificatePinner.pinnedTrustManager("alarm.example.com", pins, noPkix)
        val engine = FakeEngine(peerHost = "evil.example.com")
        assertFailsWith<HostnameMismatchException> {
            tm.checkServerTrusted(chain(leaf, root), "RSA", engine)
        }
    }

    // ============== empty/invalid host → no manager (fail-closed) ==============

    @Test
    fun `empty host throws IllegalArgumentException on manager creation`() {
        assertFailsWith<IllegalArgumentException> {
            CertificatePinner.pinnedTrustManager("", pins = emptySet(), pkixChecker = noPkix)
        }
        assertFailsWith<IllegalArgumentException> {
            CertificatePinner.pinnedTrustManager("   ", pins = emptySet(), pkixChecker = noPkix)
        }
        assertFailsWith<IllegalArgumentException> {
            CertificatePinner.systemTrustManager("", pkixChecker = noPkix)
        }
    }

    @Test
    fun `empty chain throws CertificateException`() {
        val tm = CertificatePinner.pinnedTrustManager("alarm.example.com", pins = emptySet(), pkixChecker = noPkix)
        assertFailsWith<java.security.cert.CertificateException> {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    // ============== system variant: hostname check YES, pinning NO ==============

    @Test
    fun `systemTrustManager does hostname check but accepts without pin`() {
        val root = TestCerts.mockCert("CN=unpinned-root") // NO pin
        val leaf = TestCerts.withSans("CN=alarm", "alarm.example.com")
        val tm = CertificatePinner.systemTrustManager("alarm.example.com", noPkix)
        tm.checkServerTrusted(chain(leaf, root), "RSA") // does NOT throw — no pinning
    }
    fun `systemTrustManager rejects host mismatch (fail-closed)`() {
        val root = TestCerts.mockCert("CN=root")
        val leaf = TestCerts.withSans("CN=evil", "evil.example.com")
        val tm = CertificatePinner.systemTrustManager("alarm.example.com", noPkix)
        assertFailsWith<HostnameMismatchException> {
            tm.checkServerTrusted(chain(leaf, root), "RSA")
        }
    }

    @Test
    fun `systemTrustManager rejects peerHost unequal to pinned host`() {
        val root = TestCerts.mockCert("CN=root")
        val leaf = TestCerts.withSans("CN=alarm", "alarm.example.com")
        val tm = CertificatePinner.systemTrustManager("alarm.example.com", noPkix)
        assertFailsWith<HostnameMismatchException> {
            tm.checkServerTrusted(chain(leaf, root), "RSA", FakeEngine(peerHost = "other.example.com"))
        }
    }

    // ============== per-host isolation (B1: no shared state) ==============

    @Test
    fun `two managers with different hosts are independent`() {
        val root = TestCerts.mockCert("CN=root")
        val pins = setOf(TestCerts.pinOf(root))
        val tmApi = CertificatePinner.pinnedTrustManager("alarm-api.example.com", pins, noPkix)
        val tmWeb = CertificatePinner.pinnedTrustManager("alarm.example.com", pins, noPkix)
        // leaf for alarm-api passes tmApi but fails tmWeb
        val leafApi = TestCerts.withSans("CN=x", "alarm-api.example.com")
        tmApi.checkServerTrusted(chain(leafApi, root), "RSA") // OK
        assertFailsWith<HostnameMismatchException> {
            tmWeb.checkServerTrusted(chain(leafApi, root), "RSA") // mismatch
        }
    }
}

/** Minimal SSLEngine stub with peerHost — only peerHost/session are used. */
class FakeEngine(private val peerHost: String?) : javax.net.ssl.SSLEngine() {
        override fun getPeerHost(): String? = peerHost
        override fun getPeerPort(): Int = 443
        override fun getSession(): javax.net.ssl.SSLSession = FakeSession(peerHost)
        override fun beginHandshake() {}
        override fun closeInbound() {}
        override fun closeOutbound() {}
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun getHandshakeStatus(): javax.net.ssl.SSLEngineResult.HandshakeStatus = javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        override fun getNeedClientAuth(): Boolean = false
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getUseClientMode(): Boolean = true
        override fun getWantClientAuth(): Boolean = false
        override fun isInboundDone(): Boolean = false
        override fun isOutboundDone(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation(): Boolean = true
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun setNeedClientAuth(need: Boolean) {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun setWantClientAuth(want: Boolean) {}
        override fun unwrap(src: java.nio.ByteBuffer, dst: java.nio.ByteBuffer): javax.net.ssl.SSLEngineResult = throw UnsupportedOperationException()
        override fun unwrap(src: java.nio.ByteBuffer, dsts: Array<out java.nio.ByteBuffer>, offset: Int, length: Int): javax.net.ssl.SSLEngineResult = throw UnsupportedOperationException()
        override fun wrap(src: java.nio.ByteBuffer, dst: java.nio.ByteBuffer): javax.net.ssl.SSLEngineResult = throw UnsupportedOperationException()
        override fun wrap(srcs: Array<out java.nio.ByteBuffer>, offset: Int, length: Int, dst: java.nio.ByteBuffer): javax.net.ssl.SSLEngineResult = throw UnsupportedOperationException()
        override fun getDelegatedTask(): Runnable? = null
        override fun setSSLParameters(params: javax.net.ssl.SSLParameters?) {}
        override fun getSSLParameters(): javax.net.ssl.SSLParameters = javax.net.ssl.SSLParameters()
}

/** Minimal SSLSession stub with peerHost. */
class FakeSession(private val peerHost: String?) : javax.net.ssl.SSLSession {
        override fun getPeerHost(): String? = peerHost
        override fun getPeerPort(): Int = 443
        override fun getId(): ByteArray = ByteArray(0)
        override fun getSessionContext(): javax.net.ssl.SSLSessionContext = throw UnsupportedOperationException()
        override fun getCreationTime(): Long = 0
        override fun getLastAccessedTime(): Long = 0
        override fun invalidate() {}
        override fun isValid(): Boolean = true
        override fun putValue(name: String?, value: Any?) {}
        override fun getValue(name: String?): Any? = null
        override fun removeValue(name: String?) {}
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getPeerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        @Throws(java.security.cert.CertificateEncodingException::class)
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = emptyArray()
        override fun getLocalCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun getLocalPrincipal(): java.security.Principal? = null
        override fun getPeerPrincipal(): java.security.Principal? = null
        override fun getProtocol(): String = "TLS"
        override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"
        override fun getPacketBufferSize(): Int = 16384
        override fun getApplicationBufferSize(): Int = 16384
}
