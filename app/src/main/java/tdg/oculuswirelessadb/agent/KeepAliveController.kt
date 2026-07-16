package tdg.oculuswirelessadb.agent

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

object KeepAliveController {

    private const val TAG = "MQ-KeepAlive"

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isCpuWakeLockHeld: Boolean = false
        private set

    @Volatile
    var isWifiLockHeld: Boolean = false
        private set

    /**
     * 화면을 강제로 켜는 기능이 아니라 CPU와 Wi-Fi 연결을 유지합니다.
     */
    @Synchronized
    @SuppressLint("WakelockTimeout")
    fun acquire(context: Context) {
        val appContext = context.applicationContext

        acquireCpuWakeLock(appContext)
        acquireWifiLock(appContext)

        isEnabled =
            isCpuWakeLockHeld ||
                    isWifiLockHeld

        Log.i(
            TAG,
            "KeepAlive acquired. " +
                    "cpu=$isCpuWakeLockHeld wifi=$isWifiLockHeld"
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireCpuWakeLock(context: Context) {
        try {
            if (cpuWakeLock?.isHeld == true) {
                isCpuWakeLockHeld = true
                return
            }

            val powerManager =
                context.getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MultiQuest:CpuKeepAlive"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            isCpuWakeLockHeld =
                cpuWakeLock?.isHeld == true
        } catch (e: Exception) {
            cpuWakeLock = null
            isCpuWakeLockHeld = false
            Log.e(TAG, "CPU wake lock acquire failed", e)
        }
    }

    private fun acquireWifiLock(context: Context) {
        try {
            if (wifiLock?.isHeld == true) {
                isWifiLockHeld = true
                return
            }

            val wifiManager =
                context.getSystemService(
                    Context.WIFI_SERVICE
                ) as WifiManager

            val wifiMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }

            wifiLock = wifiManager.createWifiLock(
                wifiMode,
                "MultiQuest:WifiKeepAlive"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            isWifiLockHeld =
                wifiLock?.isHeld == true
        } catch (e: Exception) {
            wifiLock = null
            isWifiLockHeld = false
            Log.e(TAG, "Wi-Fi lock acquire failed", e)
        }
    }

    @Synchronized
    fun release() {
        try {
            cpuWakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CPU wake lock release failed", e)
        } finally {
            cpuWakeLock = null
            isCpuWakeLockHeld = false
        }

        try {
            wifiLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi lock release failed", e)
        } finally {
            wifiLock = null
            isWifiLockHeld = false
        }

        isEnabled = false
        Log.i(TAG, "KeepAlive released")
    }
}