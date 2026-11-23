package io.github.calendaralarm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the PRODUCTION [SignerCheck] (Opus B2c/B3).
 * UpdateChecker.verifyApkSignature calls these functions with cert bytes;
 * here we test the logic purely.
 */
class SignerCheckTest {

    private fun cert(name: String): ByteArray = name.toByteArray()
    private fun hexOf(b: ByteArray): String = SignerCheck.sha256Hex(b)

    // ============== hex / sha256 helpers ==============

    @Test
    fun `hexToBytes converts correctly`() {
        val b = SignerCheck.hexToBytes("9c7e3bd79e6f9a52f3c3a004c743df368a64b9d846b126e1f8c98a6fc26da9c3")
        assertEquals(32, b.size)
        assertEquals(0x9c.toByte(), b[0])
    }

    @Test
    fun `pin value UpdateChecker is valid 64-hex`() {
        val pin = io.github.calendaralarm.data.UpdateChecker.PINNED_SIGNER_SHA256; assertTrue(pin.isEmpty() || Regex("^[0-9a-f]{64}$").matches(pin))
    }

    // ============== certMatchesAnyAllowed ==============

    @Test
    fun `cert matches its own hex`() {
        val c = cert("release-key")
        assertTrue(SignerCheck.certMatchesAnyAllowed(c, setOf(hexOf(c))))
    }

    @Test
    fun `cert does NOT match other hex (fail-closed)`() {
        val c = cert("release-key")
        assertFalse(SignerCheck.certMatchesAnyAllowed(c, setOf(hexOf(cert("attacker")))))
    }

    @Test
    fun `empty allowed-set never matches`() {
        assertFalse(SignerCheck.certMatchesAnyAllowed(cert("x"), emptySet()))
    }

    @Test
    fun `invalid hex in allowed-set is safely ignored (no crash)`() {
        assertFalse(SignerCheck.certMatchesAnyAllowed(cert("x"), setOf("zz-not-hex")))
    }

    // ============== archiveSignersAllowed (B3) ==============

    @Test
    fun `single signer in pinned set → accept`() {
        val key = cert("release-key")
        assertTrue(SignerCheck.archiveSignersAllowed(listOf(key), emptyList(), setOf(hexOf(key))))
    }

    @Test
    fun `key rotation with history → accept`() {
        val currentKey = cert("current-key")
        val rotatedKey = cert("rotated-old-key")
        // history contains the old key; contents-signer is the current key, pinned
        assertTrue(SignerCheck.archiveSignersAllowed(listOf(currentKey), listOf(rotatedKey), setOf(hexOf(currentKey), hexOf(rotatedKey))))
    }

    @Test
    fun `multiple signers must ALL be allowed (fail-closed)`() {
        val good = cert("good")
        val evil = cert("evil")
        // one good + one evil signer → reject
        assertFalse(SignerCheck.archiveSignersAllowed(listOf(good, evil), emptyList(), setOf(hexOf(good))))
        // both good → accept
        assertTrue(SignerCheck.archiveSignersAllowed(listOf(good, evil), emptyList(), setOf(hexOf(good), hexOf(evil))))
    }

    @Test
    fun `empty contents-signers → reject (fail-closed)`() {
        assertFalse(SignerCheck.archiveSignersAllowed(emptyList(), emptyList(), setOf(hexOf(cert("k")))))
    }

    @Test
    fun `unknown signer → reject`() {
        assertFalse(SignerCheck.archiveSignersAllowed(listOf(cert("attacker")), emptyList(), setOf(hexOf(cert("release")))))
    }

    // ============== installedSignerCoveredByArchive (B3 cross-check, SETS) ==============

    @Test
    fun `installed signer in archive contents → accept`() {
        val installed = cert("release-key")
        val archiveContents = listOf(cert("release-key"))
        assertTrue(SignerCheck.installedSignerCoveredByArchive(listOf(installed), archiveContents, emptyList()))
    }

    @Test
    fun `installed signer in archive HISTORY (rotation) → accept`() {
        val oldInstalled = cert("old-key")
        val newArchiveSigner = cert("new-key")
        val history = listOf(cert("old-key")) // old key in history of new APK
        assertTrue(SignerCheck.installedSignerCoveredByArchive(listOf(oldInstalled), listOf(newArchiveSigner), history))
    }

    @Test
    fun `installed signer NEVER in archive → reject`() {
        val installed = cert("some-other-key")
        assertFalse(SignerCheck.installedSignerCoveredByArchive(
            listOf(installed), listOf(cert("release-key")), emptyList()
        ))
    }

    @Test
    fun `empty installed signers → reject (fail-closed)`() {
        assertFalse(SignerCheck.installedSignerCoveredByArchive(emptyList(), listOf(cert("k")), emptyList()))
    }

    @Test
    fun `set comparison instead of firstOrNull - second installed signer also counts`() {
        // firstOrNull would only check the first; we check ALL
        val good = cert("good")
        val evil = cert("evil")
        val archive = listOf(good, evil)
        // both installed signers in archive → accept
        assertTrue(SignerCheck.installedSignerCoveredByArchive(listOf(good, evil), archive, emptyList()))
        // if the archive only contains 'good' but installed also 'evil' → reject
        assertFalse(SignerCheck.installedSignerCoveredByArchive(listOf(good, evil), listOf(good), emptyList()))
    }
}
