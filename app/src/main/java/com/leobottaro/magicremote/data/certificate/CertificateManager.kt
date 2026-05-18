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
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class CertificateManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_FILE = "client_keystore.p12"
        private const val TV_CERT_FILE = "tv_certificate.der"
        private val KEYSTORE_PASSWORD = "magicremote".toCharArray()
        private const val KEY_ALIAS = "client"
    }

    /** Get or create the client key pair, returned inside a PKCS12 KeyStore. */
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

    /** Get the client's X.509 certificate (DER-encoded via .encoded). */
    fun getClientCertificate(): X509Certificate? {
        return try {
            val ks = getOrCreateClientKeyStore()
            ks.getCertificate(KEY_ALIAS) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    /** Store the TV's certificate from the pairing response. */
    fun saveTvCertificate(certBytes: ByteArray) {
        context.openFileOutput(TV_CERT_FILE, Context.MODE_PRIVATE).use { it.write(certBytes) }
    }

    /** Load the stored TV certificate, or null if not paired. */
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

    /** SSL context that trusts any server cert — used during pairing. */
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

    /** SSL context with mutual TLS — used for remote commands. */
    fun createRemoteSslContext(): SSLContext? {
        val tvCert = loadTvCertificate() ?: return null

        // Load client key store
        val clientKs = getOrCreateClientKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(clientKs, KEYSTORE_PASSWORD)

        // Trust only the TV's certificate
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
        val subject = X500Name("CN=MagicRemote")
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
}
