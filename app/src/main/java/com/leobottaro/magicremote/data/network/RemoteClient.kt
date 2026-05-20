package com.leobottaro.magicremote.data.network

import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.RemoteEncoder
import com.leobottaro.magicremote.data.protocol.readFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetAddress
import javax.net.ssl.SSLSocket

class RemoteClient(private val certificateManager: CertificateManager) {

    private var socket: SSLSocket? = null
    private var outputStream: DataOutputStream? = null
    private var configured = false

    /** Connect to the TV on port 6466 using mTLS and perform config exchange. */
    suspend fun connect(host: InetAddress, port: Int = 6466): Boolean = withContext(Dispatchers.IO) {
        try {
            val sslContext = certificateManager.createRemoteSslContext() ?: return@withContext false
            val sock = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            sock.startHandshake()
            sock.soTimeout = 2000  // 2 second read timeout for config exchange
            socket = sock
            outputStream = DataOutputStream(sock.outputStream)
            val inputStream = sock.inputStream

            // 1. Read server's initial info message
            val serverInfo = readFrame(inputStream) ?: run {
                disconnect()
                return@withContext false
            }

            // 2. Send 1st config
            val config1 = RemoteEncoder.encodeRemoteConfig()
            outputStream!!.write(config1)
            outputStream!!.flush()

            // 3. Read server response to 1st config
            val response1 = readFrame(inputStream) ?: run {
                disconnect()
                return@withContext false
            }

            // 4. Send 2nd config ack
            val config2 = RemoteEncoder.encodeRemoteConfigAck()
            outputStream!!.write(config2)
            outputStream!!.flush()

            // 5. Read server status messages (power state, app info, player info)
            // Read multiple messages that the server sends
            for (i in 0 until 3) {
                val statusMsg = readFrame(inputStream) ?: break
            }

            configured = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    /** Send a key press (down then up). */
    suspend fun sendKeyPress(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val os = outputStream ?: return@withContext false

            // Press (action=1)
            val press = RemoteEncoder.keyPress(keyCode)
            os.write(press)

            // Release (action=2)
            val release = RemoteEncoder.keyRelease(keyCode)
            os.write(release)

            os.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed && configured

    fun disconnect() {
        configured = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        outputStream = null
    }
}
