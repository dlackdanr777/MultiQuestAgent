package tdg.oculuswirelessadb.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log

class AgentNsdAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "MQ-NSD"

        // WPF에서는 "_multiquest-agent._tcp.local." 로 검색
        private const val SERVICE_TYPE = "_multiquest-agent._tcp."
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun register(statusPort: Int, rtspPort: Int) {
        unregister()

        val deviceId = AgentStatus.deviceId(context)
        val shortId = deviceId.takeLast(6)

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = "MQ-${Build.MODEL.replace(" ", "_")}-$shortId"
            port = statusPort

            setAttribute("id", deviceId)
            setAttribute("model", Build.MODEL)
            setAttribute("http", statusPort.toString())
            setAttribute("rtsp", rtspPort.toString())
            setAttribute("ver", "0.1.0")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registered: ${info.serviceName} ${info.serviceType} port=${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: errorCode=$errorCode")
            }
        }

        registrationListener = listener
        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
    }

    @Synchronized
    fun unregister() {
        val listener = registrationListener ?: return

        try {
            nsdManager.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Already unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Unregister failed", e)
        } finally {
            registrationListener = null
        }
    }
}