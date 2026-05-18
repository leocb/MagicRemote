package com.leobottaro.magicremote.data.network

import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.MessageTypes
import com.leobottaro.magicremote.data.protocol.PairingMessage
import com.leobottaro.magicremote.data.protocol.readMessage
import com.leobottaro.magicremote.data.protocol.writeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
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

    /** Connect to the TV on the pairing port. */
    suspend fun connect(host: InetAddress, port: Int = 6467): Boolean = withContext(Dispatchers.IO) {
        try {
            val sslContext = certificateManager.createPairingSslContext()
            val sock = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            // Explicitly enable all cipher suites in case the TV needs them
            sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            sock.startHandshake()
            socket = sock
            outputStream = DataOutputStream(sock.outputStream)
            inputStream = DataInputStream(sock.inputStream)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    /** Send the initial pairing request. */
    suspend fun sendPairingRequest(): Boolean = withContext(Dispatchers.IO) {
        try {
            val msg = PairingMessage(
                protocolVersion = 2,
                encoding = MessageTypes.PAIRING_ENCODING_PASSCODE,
                pairingType = MessageTypes.PAIRING_TYPE_NEW,
                clientName = "MagicRemote".toByteArray()
            )
            writeAndFlush(msg.encode())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Read the TV's pairing response (should contain the challenge / server info). */
    suspend fun readPairingResponse(): PairingMessage? = withContext(Dispatchers.IO) {
        try {
            val data = readMessage(inputStream ?: return@withContext null) ?: return@withContext null
            PairingMessage.decode(data)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Send the PIN displayed on the TV. */
    suspend fun sendPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val msg = PairingMessage(
                protocolVersion = 2,
                secret = pin.toByteArray(),
                status = MessageTypes.PAIRING_STATUS_OK
            )
            writeAndFlush(msg.encode())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Send our client certificate to the TV. */
    suspend fun sendClientCertificate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val clientCert = certificateManager.getClientCertificate() ?: return@withContext false
            val msg = PairingMessage(
                protocolVersion = 2,
                status = MessageTypes.PAIRING_STATUS_OK,
                clientCertificate = clientCert.encoded
            )
            writeAndFlush(msg.encode())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Read the final pairing ack from the TV after receiving our certificate. */
    suspend fun readPairingAck(): PairingMessage? = withContext(Dispatchers.IO) {
        try {
            val data = readMessage(inputStream ?: return@withContext null) ?: return@withContext null
            PairingMessage.decode(data)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        outputStream = null
        inputStream = null
    }

    // ── Full pairing flow convenience ──

    data class PairingSession(
        val serverName: String?,
        val serverCertBytes: ByteArray?
    )

    suspend fun performPairing(host: InetAddress, pin: String): PairingResult =
        withContext(Dispatchers.IO) {
            try {
                // 1. Connect
                if (!connect(host)) return@withContext PairingResult(false, null, null)

                // 2. Send pairing request
                if (!sendPairingRequest()) {
                    disconnect()
                    return@withContext PairingResult(false, null, null)
                }

                // 3. Read TV's challenge response (should contain secret/challenge)
                val challenge = readPairingResponse() ?: run {
                    disconnect()
                    return@withContext PairingResult(false, null, null)
                }

                // 4. Send PIN
                if (!sendPin(pin)) {
                    disconnect()
                    return@withContext PairingResult(false, null, null)
                }

                // 5. Read TV response(s) — loop until we get the server certificate
                var serverCert: ByteArray? = null
                var serverName: String? = null
                for (i in 0 until 3) {
                    val response = readPairingResponse() ?: break
                    if (response.serverCertificate != null) {
                        serverCert = response.serverCertificate
                        serverName = response.serverName?.toString(Charsets.UTF_8)
                        break
                    }
                }

                if (serverCert == null) {
                    disconnect()
                    return@withContext PairingResult(false, serverName, null)
                }

                // Save server certificate
                certificateManager.saveTvCertificate(serverCert)

                // 6. Send client certificate
                if (!sendClientCertificate()) {
                    disconnect()
                    return@withContext PairingResult(false, serverName, serverCert)
                }

                // 7. Read final ack
                readPairingAck()
                disconnect()

                PairingResult(true, serverName, serverCert)
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
                PairingResult(false, null, null)
            }
        }

    private fun writeAndFlush(data: ByteArray) {
        val os = outputStream ?: throw IllegalStateException("Not connected")
        os.write(writeMessage(data))
        os.flush()
    }
}
