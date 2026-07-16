package tdg.oculuswirelessadb

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

class JmDNSAdbDiscovery(private val context: Context) {

    private var jmdns: JmDNS? = null
    private var serviceListener: ServiceListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun discoverLocalAdbService(timeoutMs: Long = 5000L): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val deferredResult = CompletableDeferred<Pair<String, Int>?>()

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("jmdns_multicast_lock").apply {
                setReferenceCounted(true)
                acquire()
            }

            val ip = getDeviceIpAddress()
            if (ip == null) {
                Log.e("JmDNS", "Could not determine IP address")
                deferredResult.complete(null)
            } else {
                jmdns = JmDNS.create(ip)

                serviceListener = object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        jmdns?.requestServiceInfo(event.type, event.name, true)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        Log.d("JmDNS", "Service removed: ${event.info}")
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val host = event.info.inetAddresses.firstOrNull()?.hostAddress
                        val port = event.info.port
                        if (host != null && port > 0 && !deferredResult.isCompleted) {
                            // 본 기기가 광고하는 ADB만 관심
                            if (host == getDeviceIpAddress()?.hostAddress) {
                                Log.i("JmDNS", "Found ADB: $host:$port")
                                deferredResult.complete(Pair(host, port))
                            }
                        }
                    }
                }

                jmdns?.addServiceListener("_adb-tls-connect._tcp.local.", serviceListener)
                jmdns?.addServiceListener("_adb_secure_connect._tcp.local.", serviceListener)
            }
        } catch (e: Exception) {
            Log.e("JmDNS", "Discovery error", e)
            deferredResult.complete(null)
        }

        val result = withTimeoutOrNull(timeoutMs) { deferredResult.await() }
        stopDiscovery()
        return@withContext result
    }

    fun stopDiscovery() {
        try {
            serviceListener?.let {
                jmdns?.removeServiceListener("_adb-tls-connect._tcp.local.", it)
                jmdns?.removeServiceListener("_adb_secure_connect._tcp.local.", it)
            }
        } catch (_: Exception) {}
        try {
            jmdns?.close()
        } catch (_: Exception) {}
        jmdns = null

        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        multicastLock = null

        Log.i("JmDNS", "Discovery stopped")
    }

    private fun getDeviceIpAddress(): InetAddress? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt == 0) return null
        val ipBytes = byteArrayOf(
            (ipInt and 0xff).toByte(),
            (ipInt shr 8 and 0xff).toByte(),
            (ipInt shr 16 and 0xff).toByte(),
            (ipInt shr 24 and 0xff).toByte()
        )
        return InetAddress.getByAddress(ipBytes)
    }
}
