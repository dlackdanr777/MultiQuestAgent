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
     * WPF 연결 유지 모드.
     *
     * 목표:
     * - 화면은 깨우지 않습니다.
     * - CPU는 suspend되지 않게 유지합니다.
     * - Wi-Fi 연결은 최대한 유지합니다.
     * - AgentService / HTTP status 서버가 화면 꺼짐 뒤에도 살아 있게 합니다.
     */
    @SuppressLint("WakelockTimeout")
    fun acquire(context: Context) {
        val appContext = context.applicationContext

        acquireCpuWakeLock(appContext)
        acquireWifiLock(appContext)

        isEnabled = isCpuWakeLockHeld || isWifiLockHeld

        Log.i(
            TAG,
            "KeepAlive acquired. cpu=$isCpuWakeLockHeld wifi=$isWifiLockHeld"
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
                context.getSystemService(Context.POWER_SERVICE) as PowerManager

            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MultiQuest:CpuKeepAlive"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            isCpuWakeLockHeld = cpuWakeLock?.isHeld == true

            Log.i(TAG, "CPU partial wake lock acquired. screen wake disabled.")
        } catch (e: Exception) {
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
                context.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

            isWifiLockHeld = wifiLock?.isHeld == true

            Log.i(TAG, "Wi-Fi lock acquired. mode=$wifiMode")
        } catch (e: Exception) {
            isWifiLockHeld = false
            Log.e(TAG, "Wi-Fi lock acquire failed", e)
        }
    }

    /**
     * AgentService가 완전히 종료될 때만 호출합니다.
     * CaptureService 종료 시에는 release하지 마십시오.
     */
    fun release() {
        try {
            cpuWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CPU wake lock release failed", e)
        } finally {
            cpuWakeLock = null
            isCpuWakeLockHeld = false
        }

        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi lock release failed", e)
        } finally {
            wifiLock = null
            isWifiLockHeld = false
        }

        isEnabled = false

        Log.i(TAG, "KeepAlive released.")
    }
}