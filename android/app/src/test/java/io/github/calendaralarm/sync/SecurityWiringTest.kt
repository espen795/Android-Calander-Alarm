package io.github.calendaralarm.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the WIRING of the security checks in production code (which trust manager
 * which client gets, whether the signer check is really called). The checks themselves are
 * tested in CertificatePinnerHostTest / SignerCheckTest; this test catches the mutation
 * "check still exists but is not (or no longer) used".
 */
class SecurityWiringTest {

    private fun src(rel: String): String {
        val candidates = listOf(
            File("src/main/java/io/github/calendaralarm/$rel"),
            File("app/src/main/java/io/github/calendaralarm/$rel")
        )
        val f = candidates.firstOrNull { it.exists() } ?: error("source not found: $rel")
        // Ignore comment lines so a commented-out call does not count
        return f.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
    }

    @Test fun `sync client uses only the pinned trust manager`() {
        val s = src("sync/SyncRepository.kt")
        val clientPart = s.substringAfter("class SyncRepository")
        assertTrue(clientPart.contains("CertificatePinner.pinnedTrustManager("))
        assertFalse("sync must never use system trust", clientPart.contains("systemTrustManager("))
    }

    @Test fun `log upload uses only the pinned trust manager`() {
        val s = src("worker/LogUploadWorker.kt")
        assertTrue(s.contains("pinnedTrustManager("))
        assertFalse(s.contains("systemTrustManager("))
    }

    @Test fun `update check calls both signer checks and fails closed`() {
        val s = src("data/UpdateChecker.kt")
        assertTrue(Regex("""if\s*\(\s*!\s*io\.github\.calendaralarm\.sync\.SignerCheck\.archiveSignersAllowed\(""").containsMatchIn(s))
        assertTrue(Regex("""if\s*\(\s*!\s*io\.github\.calendaralarm\.sync\.SignerCheck\.installedSignerCoveredByArchive\(""").containsMatchIn(s))
        assertFalse("hasSigningCertificate with a file path is wrong (package name expected)",
            Regex("""hasSigningCertificate\(\s*apkPath""").containsMatchIn(s))
    }
}
