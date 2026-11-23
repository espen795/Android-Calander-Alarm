package io.github.calendaralarm.sync

import java.security.MessageDigest

/**
 * Pure production signer-check logic (phase 3 item 4, review #35 + Opus B3).
 *
 * How it works (fail-closed):
 * - An APK is acceptable if ALL apkContentsSigners (with multiple signing:
 *   every signer) occur in the allowed pinned set — via SHA-256 of the
 *   cert bytes, compared in constant time.
 * - Key rotation (v3 signing history): the signingCertificateHistory of the archive
 *   is also taken into account — a cert from the history counts as allowed.
 * - The installed app must also occur in the history/signers of the archive
 *   (cross-check without firstOrNull: compares SETS, not the first element).
 *
 * [io.github.calendaralarm.data.UpdateChecker] calls these functions with the
 * cert bytes from PackageManager.SigningInfo; ALL logic lives here, pure and
 * testable.
 */
object SignerCheck {

    /** hex SHA-256 → bytes (32). */
    fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** SHA-256 hex of cert bytes. */
    fun sha256Hex(cert: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }

    /**
     * True if `cert` (SHA-256, constant-time) matches one of the allowed hex fingerprints.
     */
    fun certMatchesAnyAllowed(cert: ByteArray, allowedSha256Hex: Set<String>): Boolean {
        if (allowedSha256Hex.isEmpty()) return false // fail-closed
        val sha = MessageDigest.getInstance("SHA-256").digest(cert)
        return allowedSha256Hex.any { hex ->
            runCatching { MessageDigest.isEqual(sha, hexToBytes(hex)) }.getOrDefault(false)
        }
    }

    /**
     * Check an APK signing set against the allowed pins.
     *
     * @param apkContentsSigners all signers of the APK (apkContentsSigners)
     * @param signingCertificateHistory key-rotation history (may be null)
     * @param allowedSha256Hex allowed SHA-256 hex fingerprints (pinned release key(s))
     * @return true if ALL contents signers occur either in the allowed set or in the history
     *   that itself is in the allowed set — in short: every signer is explainable
     *   via the pinned key or a history cert belonging to the pinned key.
     *
     * Fail-closed: empty signers → false; empty allowed → false.
     */
    fun archiveSignersAllowed(
        apkContentsSigners: List<ByteArray>,
        signingCertificateHistory: List<ByteArray>,
        allowedSha256Hex: Set<String>
    ): Boolean {
        if (apkContentsSigners.isEmpty()) return false
        if (allowedSha256Hex.isEmpty()) return false
        // History certs that match an allowed pin are "legitimate" for rotation
        val allowedHistoryCerts: Set<String> = signingCertificateHistory
            .filter { certMatchesAnyAllowed(it, allowedSha256Hex) }
            .map { sha256Hex(it) }
            .toSet()
        // Every contents signer must match allowed OR be an allowed history cert
        return apkContentsSigners.all { signer ->
            certMatchesAnyAllowed(signer, allowedSha256Hex) ||
                allowedHistoryCerts.contains(sha256Hex(signer))
        }
    }

    /**
     * Cross-check (B3): the installed app's signer(s) must occur in the
     * archive's signingCertificateHistory (key rotation) or in the contents signers.
     * Compare SETS (no firstOrNull).
     *
     * @param installedSigners signers of the installed app
     * @param archiveContentsSigners apkContentsSigners of the APK
     * @param archiveHistory signingCertificateHistory of the APK
     */
    fun installedSignerCoveredByArchive(
        installedSigners: List<ByteArray>,
        archiveContentsSigners: List<ByteArray>,
        archiveHistory: List<ByteArray>
    ): Boolean {
        if (installedSigners.isEmpty()) return false
        val archiveCerts = (archiveContentsSigners + archiveHistory).map { sha256Hex(it) }.toSet()
        return installedSigners.all { archiveCerts.contains(sha256Hex(it)) }
    }
}
