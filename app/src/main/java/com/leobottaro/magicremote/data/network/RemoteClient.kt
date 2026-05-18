package com.leobottaro.magicremote.data.network

import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.MessageTypes
import com.leobottaro.magicremote.data.protocol.RemoteMessage
import com.leobottaro.magicremote.data.protocol.writeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetAddress
import javax.net.ssl.SSLSocket

class RemoteClient(private val certificateManager: CertificateManager) {

    private var socket: SSLSocket? = null
    private var outputStream: DataOutputStream? = null
    private var isActive = false

    /** Connect to the TV on the remote control port (6466) using mTLS. */
    suspend fun connect(host: InetAddress, port: Int = 6466): Boolean = withContext(Dispatchers.IO) {
        try {
            val sslContext = certificateManager.createRemoteSslContext() ?: return@withContext false
            val sock = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            sock.startHandshake()
            socket = sock
            outputStream = DataOutputStream(sock.outputStream)

            // Send SET_ACTIVE immediately after connecting
            val setActive = RemoteMessage(
                messageType = MessageTypes.REMOTE_SET_ACTIVE
            )
            writeAndFlush(setActive.encode())
            isActive = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    /** Send a key event (press and release). */
    suspend fun sendKeyPress(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureConnected()

            // Key down
            val downMsg = RemoteMessage(
                messageType = MessageTypes.REMOTE_KEY_EVENT,
                keyCode = keyCode,
                keyAction = MessageTypes.KEY_ACTION_DOWN
            )
            writeAndFlush(downMsg.encode())

            // Key up
            val upMsg = RemoteMessage(
                messageType = MessageTypes.REMOTE_KEY_EVENT,
                keyCode = keyCode,
                keyAction = MessageTypes.KEY_ACTION_UP
            )
            writeAndFlush(upMsg.encode())

            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    /** Check if currently connected. */
    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed

    fun disconnect() {
        isActive = false
        try {
            // Send inactive before closing
            if (socket?.isConnected == true && !socket!!.isClosed) {
                val msg = RemoteMessage(messageType = 2) // SET_ACTIVE = false
                // Actually, SET_ACTIVE with no args might be interpreted as false
                outputStream?.write(writeMessage(msg.encode()))
                outputStream?.flush()
            }
        } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        outputStream = null
    }

    private suspend fun ensureConnected() {
        if (!isConnected()) {
            throw IllegalStateException("Remote not connected")
        }
    }

    private fun writeAndFlush(data: ByteArray) {
        val os = outputStream ?: throw IllegalStateException("Not connected")
        os.write(writeMessage(data))
        os.flush()
    }
}
