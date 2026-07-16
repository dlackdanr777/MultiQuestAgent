package tdg.oculuswirelessadb.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore
import java.net.Inet4Address

object AgentStatus {

    private const val TAG = "MQ-STATUS"
    private const val AGENT_VERSION = "rtsp-agent-1.5.0"
    private const val API_VERSION = 5

    private const val BATTERY_CACHE_MS = 3_000L
    private const val THERMAL_CACHE_MS = 10_000L
    private const val STATUS_LOG_INTERVAL_MS = 10_000L

    private data class BatterySnapshot(
        val level: Int,
        val charging: Boolean,
        val chargingStatus: String,
        val temperatureTenthsC: Int
    )

    private data class ThermalSnapshot(
        val status: Int,
        val statusName: String,
        val headroom: Float
    )

    private val cacheLock = Any()

    private var batteryCachedAtElapsedMs = 0L
    private var cachedBattery = BatterySnapshot(
        level = -1,
        charging = false,
        chargingStatus = "Unknown",
        temperatureTenthsC = -1
    )

    private var thermalCachedAtElapsedMs = 0L
    private var cachedThermal = ThermalSnapshot(
        status = -1,
        statusName = "UNAVAILABLE",
        headroom = Float.NaN
    )

    private var lastStatusLogAtElapsedMs = 0L

    fun deviceId(context: Context): String {
        return StableDeviceId.get(context)
    }

    fun currentIpv4(context: Context): String? {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager

            val network = connectivityManager.activeNetwork ?: return null
            val capabilities =
                connectivityManager.getNetworkCapabilities(network) ?: return null

            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }

            val linkProperties =
                connectivityManager.getLinkProperties(network) ?: return null

            linkProperties.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "currentIpv4 failed", e)
            null
        }
    }

    fun statusJson(
        context: Context,
        statusPort: Int,
        rtspPort: Int
    ): String {
        val appContext = context.applicationContext
        val deviceId = deviceId(appContext)

        val savedName = AgentNameStore.getDeviceName(appContext)
        val deviceName = savedName.ifBlank {
            "Quest3_${deviceId.take(4)}"
        }

        val ip = currentIpv4(appContext) ?: "0.0.0.0"
        val capture = CaptureStateStore.snapshot()
        val battery = batterySnapshot(appContext)
        val thermal = thermalSnapshot(appContext)
        val power = powerSnapshot(appContext)

        val rtspUrl = capture.rtspUrl.ifBlank {
            "rtsp://$ip:$rtspPort/live/${capture.streamGeneration}"
        }

        val temperatureC = if (battery.temperatureTenthsC >= 0) {
            battery.temperatureTenthsC / 10.0
        } else {
            -1.0
        }

        val json = JSONObject()
            .put("ok", true)
            .put("apiVersion", API_VERSION)

            .put("deviceName", deviceName)
            .put("deviceId", deviceId)
            .put("model", Build.MODEL ?: "")
            .put("serial", getSerial())
            .put("ip", ip)
            .put("statusPort", statusPort)

            .put("rtspPort", rtspPort)
            .put("rtspUrl", rtspUrl)

            .put("battery", battery.level)
            .put("isCharging", battery.charging)
            .put("chargingStatus", battery.chargingStatus)
            .put("temperature", battery.temperatureTenthsC)
            .put("batteryTemperatureC", temperatureC)

            .put("interactive", power.first)
            .put("deviceIdle", power.second)
            .put("deviceUptimeMs", SystemClock.elapsedRealtime())

            .put("thermalStatus", thermal.status)
            .put("thermalStatusName", thermal.statusName)
            .put(
                "thermalHeadroom",
                if (thermal.headroom.isNaN()) JSONObject.NULL else thermal.headroom.toDouble()
            )

            .put("androidVersion", Build.VERSION.RELEASE ?: "")
            .put("buildNumber", Build.DISPLAY ?: "")
            .put("agentVersion", AGENT_VERSION)

            .put("streamState", capture.state)
            .put("streamGeneration", capture.streamGeneration)
            .put("encodedFrameCount", capture.encodedFrameCount)
            .put("lastFrameAtMs", capture.lastFrameAtMs)
            .put("frameAgeMs", capture.frameAgeMs)
            .put("stateDurationMs", capture.stateDurationMs)
            .put("rtspClientCount", capture.rtspClientCount)

            .put("streamWidth", capture.width)
            .put("streamHeight", capture.height)
            .put("streamFps", capture.fps)
            .put("streamBitrate", capture.bitrate)

            .put("captureServiceAlive", CaptureService.isServiceAlive)
            .put("captureCleanupInProgress", CaptureService.isCleanupInProgress)
            .put("activeCaptureOperationId", capture.activeOperationId)
            .put("lastCaptureError", capture.lastErrorMessage)

            .put("keepAliveEnabled", KeepAliveController.isEnabled)
            .put("cpuWakeLockHeld", KeepAliveController.isCpuWakeLockHeld)
            .put("wifiLockHeld", KeepAliveController.isWifiLockHeld)

            .put("appLaunchGuard", AppLaunchGuard.toJson())
            .put("lastApp", LastLaunchedAppStore.toDebugJson(appContext))
            .put("latestOperation", AgentOperationStore.latestJson())
            .put("timestamp", System.currentTimeMillis())

        logStatusSample(
            deviceName = deviceName,
            capture = capture,
            thermal = thermal,
            temperatureC = temperatureC
        )

        return json.toString()
    }

    private fun batterySnapshot(context: Context): BatterySnapshot {
        val now = SystemClock.elapsedRealtime()

        synchronized(cacheLock) {
            if (now - batteryCachedAtElapsedMs < BATTERY_CACHE_MS) {
                return cachedBattery
            }
        }

        val snapshot = try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            if (intent == null) {
                BatterySnapshot(-1, false, "Unknown", -1)
            } else {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percentage = if (level >= 0 && scale > 0) {
                    (level * 100f / scale).toInt()
                } else {
                    -1
                }

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

                val chargingStatus = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    BatteryManager.BATTERY_STATUS_FULL -> "Full"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                    else -> "Unknown"
                }

                val charging =
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL ||
                            plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

                BatterySnapshot(
                    level = percentage,
                    charging = charging,
                    chargingStatus = chargingStatus,
                    temperatureTenthsC = intent.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE,
                        -1
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "batterySnapshot failed", e)
            BatterySnapshot(-1, false, "Unknown", -1)
        }

        synchronized(cacheLock) {
            cachedBattery = snapshot
            batteryCachedAtElapsedMs = now
        }

        return snapshot
    }

    private fun thermalSnapshot(context: Context): ThermalSnapshot {
        val now = SystemClock.elapsedRealtime()

        synchronized(cacheLock) {
            if (now - thermalCachedAtElapsedMs < THERMAL_CACHE_MS) {
                return cachedThermal
            }
        }

        val snapshot = try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager

            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus
            } else {
                -1
            }

            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    powerManager.getThermalHeadroom(10)
                } catch (_: Exception) {
                    Float.NaN
                }
            } else {
                Float.NaN
            }

            ThermalSnapshot(
                status = status,
                statusName = thermalStatusName(status),
                headroom = headroom
            )
        } catch (e: Exception) {
            Log.w(TAG, "thermalSnapshot failed", e)
            ThermalSnapshot(-1, "UNAVAILABLE", Float.NaN)
        }

        synchronized(cacheLock) {
            cachedThermal = snapshot
            thermalCachedAtElapsedMs = now
        }

        return snapshot
    }

    private fun powerSnapshot(context: Context): Pair<Boolean, Boolean> {
        return try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager

            val idle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    powerManager.isDeviceIdleMode

            powerManager.isInteractive to idle
        } catch (_: Exception) {
            false to false
        }
    }

    private fun thermalStatusName(status: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "UNAVAILABLE"
        }

        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    private fun logStatusSample(
        deviceName: String,
        capture: CaptureStateStore.Snapshot,
        thermal: ThermalSnapshot,
        temperatureC: Double
    ) {
        val now = SystemClock.elapsedRealtime()

        synchronized(cacheLock) {
            if (now - lastStatusLogAtElapsedMs < STATUS_LOG_INTERVAL_MS) {
                return
            }
            lastStatusLogAtElapsedMs = now
        }

        Log.i(
            TAG,
            "name=$deviceName state=${capture.state} " +
                    "frames=${capture.encodedFrameCount} frameAgeMs=${capture.frameAgeMs} " +
                    "clients=${capture.rtspClientCount} thermal=${thermal.statusName} " +
                    "headroom=${thermal.headroom} batteryTempC=$temperatureC"
        )
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    fun getSerial(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val serial = Build.getSerial()
                if (serial == Build.UNKNOWN) "UNKNOWN" else serial
            } else {
                @Suppress("DEPRECATION")
                val serial = Build.SERIAL
                if (serial == Build.UNKNOWN) "UNKNOWN" else serial
            }
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }
}