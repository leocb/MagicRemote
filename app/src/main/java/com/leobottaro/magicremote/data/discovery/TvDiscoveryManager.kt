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

    companion object {
        // Android TV remote can advertise under either service type
        private val SERVICE_TYPES = listOf(
            "_androidtvremote._tcp.local.",
            "_androidtvremote2._tcp.local."
        )
    }

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
                try {
                    jmdns.requestServiceInfo(event.type, event.name, true)
                } catch (_: Exception) { }
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                try {
                    val info = event.info
                    val host = info.inetAddresses.firstOrNull() ?: return
                    val device = TvDevice(
                        name = info.name.removeSuffix("."),
                        host = host,
                        port = info.port
                    )
                    trySend(device)
                } catch (_: Exception) { }
            }
        }

        // Listen on all known Android TV remote service types
        for (type in SERVICE_TYPES) {
            try {
                jmdns.addServiceListener(type, listener)
            } catch (_: Exception) { }
        }

        awaitClose {
            for (type in SERVICE_TYPES) {
                try {
                    jmdns.removeServiceListener(type, listener)
                } catch (_: Exception) { }
            }
            try {
                jmdns.close()
            } catch (_: Exception) { }
            try {
                multicastLock.release()
            } catch (_: Exception) { }
        }
    }

    /** Prefer WiFi interface (wlan0), fall back to any non-loopback IPv4. */
    private fun resolveLocalIp(): InetAddress? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null

        // Pass 1: look for WiFi interface by name
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val name = iface.name?.lowercase() ?: ""
            // Common WiFi interface names on Android
            if (iface.isUp && (name.startsWith("wlan") || name.startsWith("eth") || name.contains("wifi"))) {
                val addr = getFirstIpv4(iface)
                if (addr != null) return addr
            }
        }

        // Pass 2: any non-loopback, non-tun (VPN), non-p2p interface
        val allInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (allInterfaces.hasMoreElements()) {
            val iface = allInterfaces.nextElement()
            val name = iface.name?.lowercase() ?: ""
            if (!iface.isUp || iface.isLoopback) continue
            if (name.startsWith("tun") || name.startsWith("p2p") || name.contains("bt-")) continue
            val addr = getFirstIpv4(iface)
            if (addr != null) return addr
        }

        return null
    }

    private fun getFirstIpv4(iface: NetworkInterface): InetAddress? {
        val addresses = iface.inetAddresses
        while (addresses.hasMoreElements()) {
            val addr = addresses.nextElement()
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr
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
