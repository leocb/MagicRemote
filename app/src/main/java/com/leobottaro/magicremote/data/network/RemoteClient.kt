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
    private var pingCount = 0

    suspend fun connect(host: InetAddress): Boolean = withContext(Dispatchers.IO) {
        hostCache = host
        val ok = establishConnection()
        Log.d("RemoteClient", "connect() returned $ok")
        ok
    }

    suspend fun sendKeyPress(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        for (attempt in 1..2) {
            try {
                val os = outputStream ?: throw Exception("Not connected")
                val t0 = System.currentTimeMillis()
                os.write(RemoteKeyEvent.keyPress(keyCode))
                os.write(RemoteKeyEvent.keyRelease(keyCode))
                os.flush()
                Log.d("RemoteClient", "Key $keyCode sent in ${System.currentTimeMillis()-t0}ms (ping=$pingCount)")
                return@withContext true
            } catch (e: Exception) {
                Log.w("RemoteClient", "attempt $attempt: ${e.message}")
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

    private fun handlePing(timeoutMs: Int): Boolean {
        val sock = socket ?: return false
        val os = outputStream ?: return false
        return try {
            sock.soTimeout = timeoutMs
            val b = sock.inputStream.read()
            if (b == 66) {
                val len = sock.inputStream.read()
                if (len > 0) sock.inputStream.skip(len.toLong())
                os.write(byteArrayOf(74, 2, 8, 25))
                os.flush()
                pingCount++
            }
            true
        } catch (_: SocketTimeoutException) { true }
        catch (_: Exception) { false }
    }

    fun runPingLoop() {
        while (configured) {
            if (!handlePing(1500)) break
        }
        Log.d("RemoteClient", "Ping loop ended (handled $pingCount pings)")
    }

    private fun establishConnection(): Boolean {
        var sock: SSLSocket? = null
        try {
            val ctx = certificateManager.createRemoteSslContext() ?: return false
            val host = hostCache ?: return false
            val tAll = System.currentTimeMillis()

            sock = ctx.socketFactory.createSocket(host, 6466) as SSLSocket
            sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            sock.startHandshake()
            val t1 = System.currentTimeMillis()

            val os = DataOutputStream(sock.outputStream)
            val ins = sock.inputStream

            sock.soTimeout = 1000
            readFrame(ins)  // server info
            os.write(RemoteConfig.encodeConfig()); os.flush()
            readFrame(ins)  // resp1
            sock.soTimeout = 50
            readFrame(ins)  // optional resp2
            os.write(RemoteConfig.encodeConfigAck()); os.flush()
            for (i in 0 until 3) { if (readFrame(ins) == null) break }

            socket = sock; outputStream = os; inputStream = ins; configured = true
            Log.d("RemoteClient", "Connected (TLS=${t1-tAll}ms total=${System.currentTimeMillis()-tAll}ms)")
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

    fun disconnect() { close() }
}
