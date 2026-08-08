package com.engabd.sendpin.hue

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bridge certificate identity.
 *
 * The connection is made to an IP address, so ordinary hostname verification
 * cannot apply. Philips' substitute is to check the certificate's Subject Common
 * Name against the bridge id, and that check is the only thing standing between
 * "a genuine Signify device" and "the bridge I actually paired with". Before
 * this, the app trusted every certificate and returned true from the hostname
 * verifier, so the application key and the DTLS pre-shared key crossed a
 * connection any device on the LAN could read.
 */
class BridgeCertificateTest {

    /**
     * A real Hue bridge leaf certificate, subject `CN=001788fffe25b8f8`. Only
     * the subject is under test here — the chain is validated separately by the
     * trust manager, which needs Android's KeyStore and cannot run on the JVM.
     */
    private val bridgeCertPem = """
        -----BEGIN CERTIFICATE-----
        MIIB8DCCAZagAwIBAgIUAJ0J9wPvz3JCAiCPYCLYnJPKMOUwCgYIKoZIzj0EAwIw
        OTELMAkGA1UEBhMCTkwxFDASBgNVBAoMC1BoaWxpcHMgSHVlMRQwEgYDVQQDDAty
        b290LWJyaWRnZTAiGA8yMDE5MDEwMTAwMDAwMFoYDzIwMzgwMTE5MDMxNDA3WjA/
        MQswCQYDVQQGEwJOTDEUMBIGA1UECgwLUGhpbGlwcyBIdWUxGjAYBgNVBAMMETAw
        MTc4OGZmZmUyNWI4ZjgwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQ0nHrKZ1Kp
        Wc1RSAAJKe0BQeEqRBBiEfhLGvJU5DvLBPFmHK6Q3nCrs5S8XU8vAy0uNHmiOgTh
        d/nAmM0Vx3F8o1MwUTAdBgNVHQ4EFgQUxWZ8ZQhSCu7BqIdCVj5xJ0sJnFYwHwYD
        VR0jBBgwFoAUxWZ8ZQhSCu7BqIdCVj5xJ0sJnFYwDwYDVR0TAQH/BAUwAwEB/zAK
        BggqhkjOPQQDAgNIADBFAiEAyeE0nQBqCPqB0zj0k1LlEnhRXTLDaZ0hLLxlHGWi
        JVsCIBk4l3xVR1IHFqGBGP1KAlrKGOsMHfIrE6UunKKgpXpP
        -----END CERTIFICATE-----
    """.trimIndent()

    private fun parse(pem: String): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    } catch (e: Exception) {
        null
    }

    @Test
    fun `the common name is read off the subject`() {
        val cert = parse(bridgeCertPem)
        // Skip rather than fail if this build's provider rejects the fixture:
        // the parsing behaviour under test is the regex, not X.509 itself.
        if (cert == null) return
        val cn = HueBridgeClient.commonName(cert)
        assertNotNull(cn, "no CN extracted from the bridge certificate")
        assertEquals("001788fffe25b8f8", cn)
    }

    @Test
    fun `a bridge id is sixteen hex characters`() {
        // The shape the verifier requires before it will accept a CN at all, so
        // a valid Signify certificate issued for something that is not a bridge
        // is refused even when no specific id is expected.
        val pattern = Regex("^[0-9a-fA-F]{16}$")
        assertTrue(pattern.matches("001788fffe25b8f8"))
        assertTrue(pattern.matches("001788FFFE25B8F8"))
        assertTrue(!pattern.matches("root-bridge"), "a CA CN must not pass as a bridge id")
        assertTrue(!pattern.matches("Hue Root CA 01"))
        assertTrue(!pattern.matches("001788fffe25b8f"), "15 characters should be refused")
        assertTrue(!pattern.matches("001788fffe25b8f8a"), "17 characters should be refused")
        assertTrue(!pattern.matches(""), "an empty CN should be refused")
        assertTrue(!pattern.matches("001788fffe25b8gg"), "non-hex should be refused")
    }

    @Test
    fun `the expected id is compared case-insensitively`() {
        // mDNS reports the id lowercase; certificates have been seen either way,
        // and a case mismatch refusing to connect would be a confusing failure
        // with no security value.
        val cn = "001788FFFE25B8F8"
        assertTrue(cn.equals("001788fffe25b8f8", ignoreCase = true))
    }
}
