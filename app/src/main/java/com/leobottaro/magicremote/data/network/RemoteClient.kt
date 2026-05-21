package com.leobottaro.magicremote.data.network

import android.util.Log
import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.RemoteConfig
import com.leobottaro.magicremote.data.protocol.RemoteKeyEvent
import com.leobottaro.magicremote.data.protocol.readFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

class RemoteClient(private val certificateManager: CertificateManager) {

    private var socket: SSLSocket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: InputStream? = null
    private var hostCache: InetAddress? = null
    private var configured = false

    /** Connect and start persistent ping loop. */
    suspend fun connect(host: InetAddress): Boolean = withContext(Dispatchers.IO) {
        hostCache = host
        establishConnection()
    }

    /** Send key press on the persistent connection. Reconnects if needed. */
    suspend fun sendKeyPress(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        // Try on existing connection first
        for (attempt in 1..2) {
            try {
                val os = outputStream ?: throw Exception("Not connected")
                os.write(RemoteKeyEvent.keyPress(keyCode))
                os.write(RemoteKeyEvent.keyRelease(keyCode))
                os.flush()
                Log.d("RemoteClient", "Key $keyCode sent")
                return@withContext true
            } catch (e: Exception) {
                Log.w("RemoteClient", "attempt $attempt failed: ${e.message}")
                close()
                if (attempt == 1 && hostCache != null) {
                    Log.d("RemoteClient", "reconnecting...")
                    if (establishConnection()) continue
                }
                return@withContext false
            }
        }
        false
    }

    /** Blocking — reads and responds to one ping. Timeout = keep waiting. */
    private fun handlePing(timeoutMs: Int): Boolean {
        val sock = socket ?: return false
        val os = outputStream ?: return false
        return try {
            sock.soTimeout = timeoutMs
            val raw = sock.inputStream
            val b = raw.read()
            if (b == 66) {
                val len = raw.read()
                if (len > 0) raw.skip(len.toLong())
                os.write(byteArrayOf(74, 2, 8, 25))
                os.flush()
            }
            true
        } catch (_: SocketTimeoutException) {
            true  // no ping yet, keep looping
        } catch (_: Exception) {
            false // connection dead
        }
    }

    /** Blocking ping loop — runs until disconnect. */
    fun runPingLoop() {
        while (configured) {
            if (!handlePing(1500)) break
        }
        Log.d("RemoteClient", "Ping loop ended")
    }

    /** Fresh connection + config exchange. */
    private fun establishConnection(): Boolean {
        var sock: SSLSocket? = null
        try {
            val ctx = certificateManager.createRemoteSslContext() ?: return false
            val host = hostCache ?: return false
            sock = ctx.socketFactory.createSocket(host, 6466) as SSLSocket
            sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            sock.startHandshake()
            val os = DataOutputStream(sock.outputStream)
            val ins = sock.inputStream

            sock.soTimeout = 2000
            readFrame(ins) ?: return false
            os.write(RemoteConfig.encodeConfig()); os.flush()
            readFrame(ins) ?: return false
            sock.soTimeout = 80
            readFrame(ins)  // optional [18,0]
            os.write(RemoteConfig.encodeConfigAck()); os.flush()
            for (i in 0 until 3) { if (readFrame(ins) == null) break }

            socket = sock; outputStream = os; inputStream = ins; configured = true
            Log.d("RemoteClient", "Connected")
            return true
        } catch (e: Exception) {
            Log.e("RemoteClient", "establishConnection failed", e)
            try { sock?.close() } catch (_: Exception) { }
            return false
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed && configured

    private fun close() {
        configured = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null; outputStream = null; inputStream = null
    }

    fun disconnect() {
        close()
    }
}
