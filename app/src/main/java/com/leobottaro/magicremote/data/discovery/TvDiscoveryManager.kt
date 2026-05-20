package com.leobottaro.magicremote.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

data class TvDevice(
    val name: String,
    val host: InetAddress,
    val port: Int
)

class TvDiscoveryManager(private val context: Context) {

    /** Emit discovered TVs as a flow. Completes when discovery stops. */
    fun discoverTvs(): Flow<TvDevice> = callbackFlow {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("jmdns-discovery")
        multicastLock.acquire()

        val jmdns = withContext(Dispatchers.IO) {
            val localIp = resolveLocalIp()
            if (localIp == null) {
                close(Exception("No network interface found. Check WiFi connection."))
                return@withContext null
            }
            JmDNS.create(localIp, "MagicRemote")
        } ?: return@callbackFlow

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inetAddresses.firstOrNull() ?: return
                val device = TvDevice(
                    name = info.name.removeSuffix("."),
                    host = host,
                    port = info.port
                )
                trySend(device)
            }
        }

        jmdns.addServiceListener("_androidtvremote._tcp.local.", listener)

        awaitClose {
            try {
                jmdns.removeServiceListener("_androidtvremote._tcp.local.", listener)
                jmdns.close()
            } catch (_: Exception) { }
            try {
                multicastLock.release()
            } catch (_: Exception) { }
        }
    }

    private fun resolveLocalIp(): InetAddress? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr
                }
            }
        }
        return null
    }

    /** Quick TCP probe to verify a TV is reachable at the given address. */
    suspend fun probeDevice(host: InetAddress, port: Int = 6467): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 2000)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
