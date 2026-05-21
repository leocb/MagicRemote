package com.leobottaro.magicremote.data.network

import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.RemoteConfig
import com.leobottaro.magicremote.data.protocol.RemoteKeyEvent
import com.leobottaro.magicremote.data.protocol.readFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import javax.net.ssl.SSLSocket

class RemoteClient(private val certificateManager: CertificateManager) {

    private var socket: SSLSocket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: InputStream? = null
    private var configured = false

    suspend fun connect(host: InetAddress, port: Int = 6466): Boolean = withContext(Dispatchers.IO) {
        try {
            val sslContext = certificateManager.createRemoteSslContext() ?: return@withContext false
            val sock = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            sock.startHandshake()
            sock.soTimeout = 2000
            socket = sock
            outputStream = DataOutputStream(sock.outputStream)
            inputStream = sock.inputStream

            if (readFrame(inputStream!!) == null) { disconnect(); return@withContext false }
            outputStream!!.write(RemoteConfig.encodeConfig())
            outputStream!!.flush()
            if (readFrame(inputStream!!) == null) { disconnect(); return@withContext false }
            outputStream!!.write(RemoteConfig.encodeConfigAck())
            outputStream!!.flush()
            for (i in 0 until 3) {
                if (readFrame(inputStream!!) == null) break
            }

            configured = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    suspend fun sendKeyPress(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val os = outputStream ?: return@withContext false
            drainPings()
            os.write(RemoteKeyEvent.keyPress(keyCode))
            os.write(RemoteKeyEvent.keyRelease(keyCode))
            os.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    private fun drainPings() {
        val `is` = inputStream ?: return
        try {
            while (`is`.available() > 0) {
                val frame = readFrame(`is`) ?: break
                if (frame.size >= 1 && frame[0].toInt() == 66) {
                    val pong = byteArrayOf(74, 2, 8, 25)
                    outputStream?.write(pong)
                    outputStream?.flush()
                }
            }
        } catch (_: Exception) { }
    }

    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed && configured

    fun disconnect() {
        configured = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        outputStream = null
        inputStream = null
    }
}
