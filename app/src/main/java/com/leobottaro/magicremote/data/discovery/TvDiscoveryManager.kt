package com.leobottaro.magicremote.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import kotlin.coroutines.resume

data class TvDevice(
    val name: String,
    val host: InetAddress,
    val port: Int
)

class TvDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var isDiscovering = false

    /** Emit discovered TVs as a flow. Completes when discovery stops. */
    fun discoverTvs(): Flow<TvDevice> = callbackFlow {
        isDiscovering = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Skip unresolvable services
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val device = TvDevice(
                    name = serviceInfo.serviceName,
                    host = serviceInfo.host ?: return,
                    port = serviceInfo.port
                )
                trySend(device)
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(Exception("Discovery failed: error $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolve only Android TV remote services by name pattern
                val name = serviceInfo.serviceName
                if (name.contains("AndroidTV", ignoreCase = true) ||
                    name.contains("GoogleTV", ignoreCase = true) ||
                    name.contains("TV", ignoreCase = true) ||
                    serviceInfo.serviceType.contains("androidtvremote")
                ) {
                    nsdManager.resolveService(serviceInfo, resolveListener)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }

        nsdManager.discoverServices(
            "_androidtvremote._tcp",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )

        awaitClose {
            if (isDiscovering) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (_: Exception) { }
                isDiscovering = false
            }
        }
    }

    /** Resolve a specific service to get its IP/port. */
    suspend fun resolveService(serviceName: String): TvDevice? = suspendCancellableCoroutine { cont ->
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_androidtvremote._tcp"
        }
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val device = TvDevice(
                    name = serviceInfo.serviceName,
                    host = serviceInfo.host ?: run {
                        if (cont.isActive) cont.resume(null)
                        return
                    },
                    port = serviceInfo.port
                )
                if (cont.isActive) cont.resume(device)
            }
        })
    }

    fun stopDiscovery() {
        // Flow's awaitClose handles cleanup
    }
}
