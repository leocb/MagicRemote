package com.leobottaro.magicremote.data.network

import android.util.Log
import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

data class PairingResult(
    val success: Boolean,
    val serverName: String? = null,
    val serverCertificate: ByteArray? = null,
    val errorMessage: String? = null
)

class PairingClient(private val certificateManager: CertificateManager) {

    private var socket: SSLSocket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private var serverCert: X509Certificate? = null
    private var initiated = false

    suspend fun initiatePairing(host: InetAddress): PairingResult = withContext(Dispatchers.IO) {
        try {
            connect(host)
            val req = PairingRequest.encode("info.kodono.assistant", "interface web")
            writeAndFlush(PoloMessage.encodeRequest(req))

            val r1 = readOrFail()?.let { PoloMessage.decode(it) }
                ?: return@withContext err("No data after PAIRING_REQUEST")
            if (r1.status != StatusCode.OK) return@withContext err("TV rejected: status=${r1.status}")
            if (r1.fieldNum != 11) return@withContext err("Expected ack(field 11), got field ${r1.fieldNum}")

            val opts = OptionsMsg.encode()
            writeAndFlush(PoloMessage.encodeOptions(opts))
            val r2 = readOrFail()?.let { PoloMessage.decode(it) }
                ?: return@withContext err("No data after OPTIONS")
            if (r2.status != StatusCode.OK) return@withContext err("TV rejected OPTIONS: status=${r2.status}")

            val cfg = ConfigurationMsg.encode()
            writeAndFlush(PoloMessage.encodeConfiguration(cfg))
            val r3 = readOrFail()?.let { PoloMessage.decode(it) }
                ?: return@withContext err("No data after CONFIGURATION")
            if (r3.status != StatusCode.OK) return@withContext err("TV rejected CONFIG: status=${r3.status}")
            if (r3.fieldNum != 31) return@withContext err("Expected CONFIG_ACK(field 31), got field ${r3.fieldNum}")

            serverCert = try {
                socket?.session?.peerCertificates?.getOrNull(0) as? X509Certificate
            } catch (_: Exception) { null }
            if (serverCert == null) return@withContext err("No server TLS certificate")

            initiated = true
            PairingResult(true)
        } catch (e: Exception) {
            disconnect()
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "Connection timed out"
                is java.net.ConnectException -> "Connection refused"
                is javax.net.ssl.SSLException -> "TLS: ${e.message}"
                else -> "${e::class.simpleName}: ${e.message?.take(100)}"
            }
            Log.e("PairingClient", "initiatePairing failed", e)
            PairingResult(false, errorMessage = msg)
        }
    }

    suspend fun completePairing(pin: String): PairingResult = withContext(Dispatchers.IO) {
        if (!initiated) return@withContext PairingResult(false, errorMessage = "Pairing not initiated")
        try {
            val cc = certificateManager.getClientRsaComponents()
                ?: return@withContext PairingResult(false, errorMessage = "No client RSA key")
            val sc = certificateManager.extractRsaComponents(serverCert!!)
                ?: return@withContext PairingResult(false, errorMessage = "Cannot extract server RSA key")
            val secret = certificateManager.computeSecret(cc, sc, pin)

            writeAndFlush(PoloMessage.encodeSecret(SecretMsg.encode(secret)))
            val certBytes = serverCert!!.encoded

            // SECRET_ACK read is non-fatal — TV already confirmed on screen
            try {
                val resp4 = readOrFail()
                if (resp4 != null) {
                    val r4 = PoloMessage.decode(resp4)
                    if (r4.status != StatusCode.OK || r4.fieldNum != 41) {
                        Log.d("PairingClient", "SECRET_ACK unexpected: status=${r4.status} field=${r4.fieldNum}")
                    }
                }
            } catch (e: Exception) {
                Log.d("PairingClient", "SECRET_ACK read failed (non-fatal): ${e.message}")
            }

            certificateManager.saveTvCertificate(certBytes)
            disconnect()
            Log.d("PairingClient", "Pairing SUCCESS")
            PairingResult(true, "Android TV", certBytes)
        } catch (e: Exception) {
            Log.e("PairingClient", "completePairing failed", e)
            disconnect()
            PairingResult(false, errorMessage = e.message)
        }
    }

    fun disconnect() {
        initiated = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        outputStream = null
        inputStream = null
        serverCert = null
    }

    private fun connect(host: InetAddress) {
        val ctx = certificateManager.createPairingSslContext()
        val sock = ctx.socketFactory.createSocket(host, 6467) as SSLSocket
        sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        sock.startHandshake()
        sock.soTimeout = 5000
        socket = sock
        outputStream = DataOutputStream(sock.outputStream)
        inputStream = DataInputStream(sock.inputStream)
    }

    private fun writeAndFlush(data: ByteArray) {
        outputStream?.write(data)
        outputStream?.flush()
    }

    private fun readOrFail(): ByteArray? {
        return readFrame(inputStream ?: return null)
    }

    private fun err(msg: String): PairingResult {
        disconnect()
        return PairingResult(false, errorMessage = msg)
    }
}
