package com.leobottaro.magicremote.data.network

import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.PairingEncoder
import com.leobottaro.magicremote.data.protocol.readFrame
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
    val serverCertificate: ByteArray? = null
)

class PairingClient(private val certificateManager: CertificateManager) {

    private var socket: SSLSocket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private var serverCertificate: X509Certificate? = null

    suspend fun performPairing(host: InetAddress, pin: String): PairingResult =
        withContext(Dispatchers.IO) {
            try {
                // 1. Connect TLS to port 6467
                connect(host)

                // 2. Send client config (Message 1)
                val configMsg = PairingEncoder.encodeClientConfig("MagicRemote", "MagicRemote")
                writeAndFlush(configMsg)

                // 3. Read first ack — TV should respond with protocol_version + status + empty ack (field 11)
                val ack1 = readFrame(inputStream ?: return@withContext error("No input stream"))
                    ?: return@withContext error("Failed to read first ack")

                // 4. Send options (Message 2)
                val optionsMsg = PairingEncoder.encodeOptions()
                writeAndFlush(optionsMsg)

                // 5. Read options ack
                val ack2 = readFrame(inputStream ?: return@withContext error("No input stream"))
                    ?: return@withContext error("Failed to read options ack")

                // 6. Send configuration (Message 3)
                val configMsg2 = PairingEncoder.encodeConfiguration()
                writeAndFlush(configMsg2)

                // 7. Read configuration ack — TV should respond with field 11 (empty ack)
                val ack3 = readFrame(inputStream ?: return@withContext error("No input stream"))
                    ?: return@withContext error("Failed to read config ack")

                // At this point the TV shows the pairing code on screen

                // 8. Extract server certificate from TLS session
                serverCertificate = try {
                    socket?.session?.peerCertificates?.getOrNull(0) as? X509Certificate
                } catch (_: Exception) { null }

                if (serverCertificate == null) {
                    return@withContext error("Could not get server certificate from TLS handshake")
                }

                // 9. Compute secret
                val clientComponents = certificateManager.getClientRsaComponents()
                    ?: return@withContext error("No client RSA key")
                val serverComponents = certificateManager.extractRsaComponents(serverCertificate!!)
                    ?: return@withContext error("Could not extract server RSA key")

                val secret = certificateManager.computeSecret(clientComponents, serverComponents, pin)

                // 10. Send the computed secret (Message 4)
                val secretMsg = PairingEncoder.encodeSecret(secret)
                writeAndFlush(secretMsg)

                // 11. Read final response — should contain server_certificate or status
                val finalResponse = readFrame(inputStream ?: return@withContext error("No input stream"))
                disconnect()

                val serverCertBytes = serverCertificate!!.encoded
                certificateManager.saveTvCertificate(serverCertBytes)

                PairingResult(true, "Android TV", serverCertBytes)
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
                PairingResult(false, null, null)
            }
        }

    private fun connect(host: InetAddress) {
        val sslContext = certificateManager.createPairingSslContext()
        val sock = sslContext.socketFactory.createSocket(host, 6467) as SSLSocket
        sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        sock.startHandshake()
        sock.soTimeout = 3000  // 3 second read timeout
        socket = sock
        outputStream = DataOutputStream(sock.outputStream)
        inputStream = DataInputStream(sock.inputStream)
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        outputStream = null
        inputStream = null
    }

    private fun writeAndFlush(data: ByteArray) {
        val os = outputStream ?: throw IllegalStateException("Not connected")
        os.write(data)
        os.flush()
    }

    private fun error(msg: String): PairingResult {
        disconnect()
        return PairingResult(false, null, null)
    }
}
