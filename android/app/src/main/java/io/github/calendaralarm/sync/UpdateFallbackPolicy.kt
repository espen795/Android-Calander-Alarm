package io.github.calendaralarm.sync

/**
 * Production fallback policy for the update recovery path (phase 3 item 3, #7c).
 *
 * Opus review B2 (2026-11-22): the old test tested an enum that only existed in the test.
 * This object IS the production function that [io.github.calendaralarm.data.UpdateChecker]
 * actually calls; sync and log-upload NEVER call it (fail-closed).
 *
 * Rules:
 * - ONLY the UPDATE channel may fall back to system trust without pinning.
 * - ONLY on a real PIN mismatch ([PinMismatchException] or a
 *   SSLPeerUnverifiedException/SSLHandshakeException with a pinning-failure message).
 * - Hostname errors ([HostnameMismatchException]) NEVER trigger the fallback —
 *   a certificate for a different host is not a CA swap and must not
 *   be "fixed" with a weaker check.
 * - Network errors (DNS, timeout, connect refused) NEVER trigger the fallback.
 */
object UpdateFallbackPolicy {

    /** The three network channels in the app. */
    enum class Channel { SYNC, LOG_UPLOAD, UPDATE }

    /**
     * May this channel fall back to system trust (without pinning) on this exception?
     */
    fun shouldFallback(channel: Channel, e: Throwable): Boolean {
        if (channel != Channel.UPDATE) return false // sync/log-upload: fail-closed
        return isPinFailure(e)
    }

    /**
     * True if the exception is a real pin failure (only these trigger the fallback
     * for the update channel). Hostname errors and network errors → false.
     */
    fun isPinFailure(e: Throwable): Boolean {
        // Our own PinMismatchException is the primary signal
        if (e is PinMismatchException) return true
        // Hostname error explicitly EXCLUDED (no fallback)
        if (e is HostnameMismatchException) return false
        // SSLPeerUnverifiedException / SSLHandshakeException only when it is about
        // pinning (message check — fail-closed: when in doubt false)
        if (e is javax.net.ssl.SSLPeerUnverifiedException) {
            return e.message?.contains("pin", ignoreCase = true) == true
        }
        if (e is javax.net.ssl.SSLHandshakeException) {
            return e.message?.contains("pin", ignoreCase = true) == true
        }
        // Check the caused-by chain (Ktor sometimes wraps exceptions) — with
        // a depth limit. PinMismatchException in the chain counts; a
        // HostnameMismatchException in the chain wins (no fallback).
        var cause: Throwable? = e.cause
        var depth = 0
        while (cause != null && depth < 5) {
            if (cause is PinMismatchException) return true
            if (cause is HostnameMismatchException) return false
            if (cause is javax.net.ssl.SSLPeerUnverifiedException &&
                cause.message?.contains("pin", ignoreCase = true) == true
            ) return true
            if (cause is javax.net.ssl.SSLHandshakeException &&
                cause.message?.contains("pin", ignoreCase = true) == true
            ) return true
            cause = cause.cause
            depth++
        }
        return false
    }
}
