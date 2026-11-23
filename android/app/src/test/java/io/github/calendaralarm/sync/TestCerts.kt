package io.github.calendaralarm.sync

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Shared helper for unit tests: mock X509Certificates with a unique
 * RSA key per cert (so the SPKI hash is unique) and configurable SANs/CN.
 * No sun.* internals — purely implement the X509Certificate abstraction.
 */
object TestCerts {

    fun mockCert(dn: String, dnsSans: List<String>? = null): X509Certificate {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val publicKey: PublicKey = kpg.generateKeyPair().public
        return object : X509Certificate() {
            override fun getPublicKey(): PublicKey = publicKey
            override fun getSubjectX500Principal(): X500Principal = X500Principal(dn)
            override fun getSubjectAlternativeNames(): MutableCollection<List<Any>>? =
                dnsSans?.map { mutableListOf<Any>(2, it) }?.toMutableList()
            override fun getIssuerX500Principal(): X500Principal = X500Principal("CN=issuer")
            override fun getNotBefore(): Date = Date(0)
            override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
            override fun getSerialNumber(): BigInteger = BigInteger.ONE
            override fun getSigAlgName(): String = "SHA256withRSA"
            override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
            override fun getSigAlgParams(): ByteArray? = null
            override fun getSignature(): ByteArray = ByteArray(1)
            override fun getVersion(): Int = 3
            override fun getBasicConstraints(): Int = -1
            override fun getEncoded(): ByteArray = ByteArray(1)
            override fun verify(pubKey: PublicKey?) {}
            override fun verify(pubKey: PublicKey?, sigProvider: String?) {}
            override fun toString(): String = "MockCert($dn)"
            override fun checkValidity() {}
            override fun checkValidity(validityDate: Date?) {}
            @Deprecated("deprecated")
            override fun getIssuerDN(): Principal = X500Principal("CN=issuer")
            @Deprecated("deprecated")
            override fun getSubjectDN(): Principal = X500Principal(dn)
            override fun getTBSCertificate(): ByteArray = ByteArray(1)
            override fun getIssuerUniqueID(): BooleanArray? = null
            override fun getSubjectUniqueID(): BooleanArray? = null
            override fun getKeyUsage(): BooleanArray? = null
            @Throws(CertificateEncodingException::class)
            override fun getExtensionValue(oid: String?): ByteArray? = null
            override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
            override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
            override fun hasUnsupportedCriticalExtension(): Boolean = false
        }
    }

    /** Mock cert with SAN dNSName list. */
    fun withSans(cn: String, vararg dnsSans: String): X509Certificate =
        mockCert(cn, dnsSans.toList())

    /** SPKI SHA-256 pin (base64) of a mock cert — to put in a test pin set. */
    fun pinOf(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(CertificatePins.spkiSha256(cert))
}
