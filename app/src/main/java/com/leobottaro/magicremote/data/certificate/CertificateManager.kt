package com.leobottaro.magicremote.data.certificate

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class RsaKeyComponents(
    val modulus: ByteArray,
    val exponent: ByteArray
)

class CertificateManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_FILE = "client_keystore.p12"
        private const val TV_CERT_FILE = "tv_certificate.der"
        private val KEYSTORE_PASSWORD = "magicremote".toCharArray()
        private const val KEY_ALIAS = "client"
    }

    // ── Client key management ──

    fun getOrCreateClientKeyStore(): KeyStore {
        val ks = loadKeyStore()
        if (!ks.containsAlias(KEY_ALIAS)) {
            val keyPair = generateKeyPair()
            val cert = generateSelfSignedCertificate(keyPair)
            ks.setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(cert))
            saveKeyStore(ks)
        }
        return ks
    }

    fun getClientCertificate(): X509Certificate? {
        return try {
            val ks = getOrCreateClientKeyStore()
            ks.getCertificate(KEY_ALIAS) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    /** RSA components from our client certificate (for secret computation). */
    fun getClientRsaComponents(): RsaKeyComponents? {
        val cert = getClientCertificate() ?: return null
        return extractRsaComponents(cert)
    }

    /** RSA components from the TV's certificate (for secret computation). */
    fun getServerRsaComponents(): RsaKeyComponents? {
        val cert = loadTvCertificate() ?: return null
        return extractRsaComponents(cert)
    }

    /** Extract RSA components from a peer certificate during TLS. */
    fun extractRsaComponents(cert: X509Certificate): RsaKeyComponents? {
        val pubKey = cert.publicKey
        if (pubKey !is RSAPublicKey) return null
        return RsaKeyComponents(
            modulus = removeLeadingNull(pubKey.modulus.toByteArray()),
            exponent = removeLeadingNull(pubKey.publicExponent.toByteArray())
        )
    }

    /** Compute the SHA-256 secret for pairing. */
    fun computeSecret(
        clientComponents: RsaKeyComponents,
        serverComponents: RsaKeyComponents,
        pairingCode: String
    ): ByteArray {
        // Take the last 4 hex chars of the code and convert to 2 binary bytes
        val codeHex = pairingCode.substring(2, 6) // chars 2-5 (skip first 2, take 4)
        val codeBytes = codeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(clientComponents.modulus)
        digest.update(clientComponents.exponent)
        digest.update(serverComponents.modulus)
        digest.update(serverComponents.exponent)
        digest.update(codeBytes)
        return digest.digest()
    }

    // ── TV certificate storage ──

    fun saveTvCertificate(certBytes: ByteArray) {
        context.openFileOutput(TV_CERT_FILE, Context.MODE_PRIVATE).use { it.write(certBytes) }
    }

    fun loadTvCertificate(): X509Certificate? {
        return try {
            val bytes = context.openFileInput(TV_CERT_FILE).use { it.readBytes() }
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    fun isPaired(): Boolean = context.getFileStreamPath(TV_CERT_FILE).exists()

    fun clearPairing() {
        context.deleteFile(TV_CERT_FILE)
        context.deleteFile(KEYSTORE_FILE)
    }

    // ── SSL contexts ──

    fun createPairingSslContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?, authType: String?
            ) = Unit
            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?, authType: String?
            ) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx
    }

    fun createRemoteSslContext(): SSLContext? {
        val tvCert = loadTvCertificate() ?: return null

        val clientKs = getOrCreateClientKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(clientKs, KEYSTORE_PASSWORD)

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null)
        trustStore.setCertificateEntry("tv", tvCert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        return ctx
    }

    // ── Private helpers ──

    private fun loadKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        try {
            context.openFileInput(KEYSTORE_FILE).use { ks.load(it, KEYSTORE_PASSWORD) }
        } catch (_: Exception) {
            ks.load(null, KEYSTORE_PASSWORD)
        }
        return ks
    }

    private fun saveKeyStore(ks: KeyStore) {
        context.openFileOutput(KEYSTORE_FILE, Context.MODE_PRIVATE).use {
            ks.store(it, KEYSTORE_PASSWORD)
        }
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        return gen.generateKeyPair()
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val subject = X500Name("CN=MagicRemote,O=Magic Remote App,C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 10L * 365 * 24 * 60 * 60 * 1000)

        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    /** Remove leading 0x00 byte from BigInteger.toByteArray() if present (sign byte). */
    private fun removeLeadingNull(bytes: ByteArray): ByteArray {
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else bytes
    }
}
