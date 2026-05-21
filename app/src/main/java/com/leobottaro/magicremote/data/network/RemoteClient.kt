package com.leobottaro.magicremote.data.network

import android.util.Log
import com.leobottaro.magicremote.data.certificate.CertificateManager
import com.leobottaro.magicremote.data.protocol.RemoteConfig
import com.leobottaro.magicremote.data.protocol.RemoteKeyEvent
import com.leobottaro.magicremote.data.protocol.readFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetAddress
import javax.net.ssl.SSLSocket

class RemoteClient(private val certificateManager: CertificateManager) {

    private var lastHost: InetAddress? = null

    suspend fun connect(host: InetAddress): Boolean = withContext(Dispatchers.IO) {
        lastHost = host
        connectAndSend(null)
    }

    suspend fun sendKeyPress(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        val host = lastHost ?: return@withContext false
        connectAndSend(keyCode)
    }

    private fun connectAndSend(keyCode: Int?): Boolean {
        var sock: SSLSocket? = null
        try {
            val ctx = certificateManager.createRemoteSslContext() ?: return false
            val host = lastHost ?: return false
            sock = ctx.socketFactory.createSocket(host, 6466) as SSLSocket
            sock.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            sock.startHandshake()

            val os = DataOutputStream(sock.outputStream)
            val ins = sock.inputStream
            sock.soTimeout = 5000

            // Wiki: server sends info first, then we send config1
            val serverInfo = readFrame(ins) ?: return false

            // Send config1
            os.write(RemoteConfig.encodeConfig()); os.flush()

            // Read two responses: [10,3,8,255,4] and [18,0]
            val resp1 = readFrame(ins) ?: return false
            val resp2 = readFrame(ins) // [18,0] — trigger for config2

            // Send config2 ACK (field 2 per wiki)
            os.write(RemoteConfig.encodeConfigAck()); os.flush()

            // Drain status messages (power, app, player)
            for (i in 0 until 5) { if (readFrame(ins) == null) break }

            // Send key command if requested
            if (keyCode != null) {
                os.write(RemoteKeyEvent.keyPress(keyCode))
                os.write(RemoteKeyEvent.keyRelease(keyCode))
                os.flush()
                Log.d("RemoteClient", "Key $keyCode sent")
            }
            return true
        } catch (e: Exception) {
            Log.e("RemoteClient", "connectAndSend failed", e)
            return false
        } finally {
            try { sock?.close() } catch (_: Exception) { }
        }
    }

    fun disconnect() { }
}
