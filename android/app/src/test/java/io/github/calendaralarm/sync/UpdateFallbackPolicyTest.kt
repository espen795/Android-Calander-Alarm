package io.github.calendaralarm.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the PRODUCTION [UpdateFallbackPolicy] (Opus B2b).
 *
 * The policy is really called by UpdateChecker.getWithPinFallback().
 * Sync and log upload NEVER call shouldFallback — that is fail-closed by
 * construction (the only call site is the UPDATE channel; see test at the bottom).
 */
class UpdateFallbackPolicyTest {

    // ============== Fallback ONLY for the update channel ==============

    @Test
    fun `fallback allowed ONLY for the update channel on pin failure`() {
        val pinFail = PinMismatchException("no SPKI hash matched")
        assertTrue(UpdateFallbackPolicy.shouldFallback(UpdateFallbackPolicy.Channel.UPDATE, pinFail))
        assertFalse(UpdateFallbackPolicy.shouldFallback(UpdateFallbackPolicy.Channel.SYNC, pinFail))
        assertFalse(UpdateFallbackPolicy.shouldFallback(UpdateFallbackPolicy.Channel.LOG_UPLOAD, pinFail))
    }

    // ============== Only pin failures trigger ==============

    @Test
    fun `PinMismatchException triggers fallback`() {
        assertTrue(UpdateFallbackPolicy.isPinFailure(PinMismatchException("pinning failed for host=x")))
    }

    @Test
    fun `SSLPeerUnverifiedException with pin message triggers fallback`() {
        assertTrue(UpdateFallbackPolicy.isPinFailure(
            javax.net.ssl.SSLPeerUnverifiedException("Certificate pinning failed for host=x")
        ))
    }

    @Test
    fun `SSLHandshakeException with pin message triggers fallback via caused-by chain`() {
        // A Ktor-style wrap: generic exception with PinMismatchException as cause
        val pin = PinMismatchException("no pin matched")
        val wrapped = RuntimeException("request failed", pin)
        assertTrue(UpdateFallbackPolicy.isPinFailure(wrapped))
        // direct pin in the message of the SSLHandshakeException itself
        assertTrue(UpdateFallbackPolicy.isPinFailure(
            javax.net.ssl.SSLHandshakeException("Certificate pinning failed for host=x")
        ))
    }

    @Test
    fun `hostname failure NEVER triggers fallback`() {
        assertFalse(UpdateFallbackPolicy.isPinFailure(
            HostnameMismatchException("cert is not for 'evil.example'")
        ))
        assertFalse(UpdateFallbackPolicy.shouldFallback(
            UpdateFallbackPolicy.Channel.UPDATE,
            HostnameMismatchException("mismatch")
        ))
    }

    @Test
    fun `SSLPeerUnverifiedException WITHOUT pin in message triggers NO fallback (fail-closed)`() {
        assertFalse(UpdateFallbackPolicy.isPinFailure(
            javax.net.ssl.SSLPeerUnverifiedException("peer not authenticated")
        ))
    }

    @Test
    fun `network errors NEVER trigger the fallback`() {
        assertFalse(UpdateFallbackPolicy.isPinFailure(java.net.UnknownHostException("dns")))
        assertFalse(UpdateFallbackPolicy.isPinFailure(java.net.ConnectException("refused")))
        assertFalse(UpdateFallbackPolicy.isPinFailure(java.net.SocketTimeoutException("timeout")))
        assertFalse(UpdateFallbackPolicy.isPinFailure(IllegalStateException("generic")))
        assertFalse(UpdateFallbackPolicy.isPinFailure(RuntimeException("boom")))
        // Not even for the update channel
        assertFalse(UpdateFallbackPolicy.shouldFallback(
            UpdateFallbackPolicy.Channel.UPDATE, java.net.SocketTimeoutException("timeout")
        ))
    }

    @Test
    fun `HTTP errors are not exceptions — no fallback`() {
        // An HTTP 503 is a response, not an exception → shouldFallback is not even called.
        // If it DOES come in as an exception (e.g. wrapped), it is not a pin failure:
        assertFalse(UpdateFallbackPolicy.isPinFailure(object : Exception("HTTP 503") {}))
    }

    // ============== Sync channel is provably fail-closed ==============

    @Test
    fun `sync channel fails on pin mismatch without fallback (fail-closed)`() {
        val pinMismatch = PinMismatchException("Certificate pinning failed for host=alarm-api.example.com")
        assertTrue(UpdateFallbackPolicy.isPinFailure(pinMismatch)) // recognized as pin failure
        assertFalse(UpdateFallbackPolicy.shouldFallback(UpdateFallbackPolicy.Channel.SYNC, pinMismatch)) // but NO fallback
        assertFalse(UpdateFallbackPolicy.shouldFallback(UpdateFallbackPolicy.Channel.LOG_UPLOAD, pinMismatch))
    }

    /**
     * Source-code guarantee: shouldFallback is ONLY called with Channel.UPDATE.
     * This test reads the production sources and fails if a call with SYNC or
     * LOG_UPLOAD appears — so a future refactor that lets sync fall back
     * turns red.
     */
    @Test
    fun `shouldFallback is called in main ONLY with Channel UPDATE`() {
        val mainDir = java.io.File("src/main/java/io/github/calendaralarm")
        // In unit tests the working dir is the module dir (app/)
        val root = generateSequence(mainDir.takeIf { it.exists() } ?: java.io.File("app/src/main/java/io/github/calendaralarm")) { it }
            .first()
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("shouldFallback(") &&
                    (line.contains("Channel.SYNC") || line.contains("Channel.LOG_UPLOAD"))
                ) {
                    offenders += "${f.name}:${i + 1}: $line"
                }
            }
        }
        assertTrue(
            "shouldFallback must NEVER be called with SYNC/LOG_UPLOAD, found: $offenders",
            offenders.isEmpty()
        )
    }
}
