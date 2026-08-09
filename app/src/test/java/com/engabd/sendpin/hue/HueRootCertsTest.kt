package com.engabd.sendpin.hue

import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled Hue root certificates have to be loadable.
 *
 * Every request to the bridge is validated against them, so a bundle that fails
 * to parse takes the whole direct path with it. It did exactly that once: the
 * file was valid and present in the APK, but was handed to the certificate
 * factory as a raw resource stream, which returned zero certificates on device
 * while parsing correctly everywhere else. The loader now reads the resource
 * fully into memory first.
 *
 * This reads the file from the source tree rather than through `R.raw`, since a
 * JVM test has no resource table. It cannot reproduce the Android stream
 * behaviour — only a device can — but it does guard the thing that is checkable
 * here: that the bundle is present, is real, and is what it claims to be.
 */
class HueRootCertsTest {

    private val pem = File("src/main/res/raw/hue_root_certs.pem")

    private fun parse(): List<X509Certificate> {
        val bytes = pem.readBytes()
        return CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
            .filterIsInstance<X509Certificate>()
    }

    @Test
    fun `the bundle exists and is not empty`() {
        assertTrue(pem.exists(), "hue_root_certs.pem is missing from res/raw")
        assertTrue(pem.length() > 0, "hue_root_certs.pem is empty")
    }

    @Test
    fun `both signify roots parse`() {
        val certs = parse()
        assertEquals(2, certs.size, "expected both Signify roots, parsed ${certs.size}")
    }

    @Test
    fun `the roots are the ones philips documents`() {
        // Named in the Using HTTPS page: the one currently in use, and the
        // secondary they may switch to. A bundle quietly swapped for something
        // else would still parse, so the subjects are checked by name.
        val names = parse().mapNotNull { HueBridgeClient.commonName(it) }
        assertTrue("root-bridge" in names, "the active Signify root is missing: $names")
        assertTrue(names.any { it.contains("Hue Root CA", ignoreCase = true) }, "the secondary root is missing: $names")
    }

    @Test
    fun `the roots are certificate authorities`() {
        // basicConstraints pathLen >= 0 means a CA. A leaf certificate here
        // would validate nothing.
        for (cert in parse()) {
            assertTrue(
                cert.basicConstraints >= 0,
                "${HueBridgeClient.commonName(cert)} is not a CA certificate",
            )
        }
    }

    @Test
    fun `each block decodes as DER independently`() {
        // What the loader actually does on device: locate the blocks, decode the
        // base64, parse each one on its own. Both must survive that.
        val text = pem.readBytes().toString(Charsets.US_ASCII)
        val blocks = Regex(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(2, blocks.size, "expected two PEM blocks")
        val factory = CertificateFactory.getInstance("X.509")
        for ((i, block) in blocks.withIndex()) {
            val der = java.util.Base64.getDecoder().decode(block.filterNot { it.isWhitespace() })
            val cert = factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            assertTrue(
                HueBridgeClient.commonName(cert) != null,
                "block $i decoded to a certificate with no subject",
            )
        }
    }

    @Test
    fun `the roots have not expired`() {
        // They run to 2038, so this is a slow alarm rather than a live risk —
        // but an expired root fails every bridge connection at once, and the
        // symptom would be indistinguishable from a network fault.
        val now = java.util.Date()
        for (cert in parse()) {
            assertTrue(
                cert.notAfter.after(now),
                "${HueBridgeClient.commonName(cert)} expired on ${cert.notAfter}",
            )
        }
    }
}
