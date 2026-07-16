package tdg.oculuswirelessadb.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import tdg.oculuswirelessadb.capture.CaptureStateStore
import java.net.Inet4Address

object AgentStatus {

    private const val TAG = "MQ-CMD"
    private const val AGENT_VERSION = "rtsp-agent-1.0.0"

    /**
     * StableDeviceId를 사용하여 앱 삭제 후에도 유지되는 고유 ID를 반환합니다.
     */
    fun deviceId(context: Context): String {
        return StableDeviceId.get(context)
    }

    /**
     * 현재 기기의 IPv4 주소를 반환합니다.
     */
    fun currentIpv4(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null

            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }

            val linkProperties = cm.getLinkProperties(network) ?: return null

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

    /**
     * 배터리 잔량을 반환합니다.
     */
    fun batteryLevel(context: Context): Int {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return -1

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            if (level < 0 || scale <= 0) {
                -1
            } else {
                (level * 100 / scale.toFloat()).toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "batteryLevel failed", e)
            -1
        }
    }

    /**
     * 충전 중 여부를 반환합니다.
     */
    fun isCharging(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return false

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            val isChargingStatus =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

            val isPlugged =
                plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                        plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                        plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

            isChargingStatus || isPlugged
        } catch (e: Exception) {
            Log.e(TAG, "isCharging failed", e)
            false
        }
    }

    /**
     * 충전 상태 문자열을 반환합니다.
     * WPF 표시용입니다.
     */
    fun chargingStatus(context: Context): String {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return "Unknown"

            when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            Log.e(TAG, "chargingStatus failed", e)
            "Unknown"
        }
    }

    /**
     * 배터리 온도를 반환합니다.
     * Android BatteryManager.EXTRA_TEMPERATURE는 0.1도 단위입니다.
     */
    fun batteryTemperature(context: Context): Int {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return -1

            intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        } catch (e: Exception) {
            Log.e(TAG, "batteryTemperature failed", e)
            -1
        }
    }

    /**
     * WPF 클라이언트가 기기 상태를 파악하기 위해 호출하는 JSON 데이터를 생성합니다.
     */
    fun statusJson(context: Context, statusPort: Int, rtspPort: Int): String {
        val deviceId = deviceId(context)

        var deviceName = AgentNameStore.getDeviceName(context)
        if (deviceName.isBlank()) {
            deviceName = "Quest3_${deviceId.take(4)}"
        }

        val ip = currentIpv4(context) ?: "0.0.0.0"

        val rtspUrl = if (CaptureStateStore.rtspUrl.isBlank()) {
            "rtsp://$ip:$rtspPort/live"
        } else {
            CaptureStateStore.rtspUrl
        }

        val frameAgeMs = CaptureStateStore.getFrameAgeMs()
        val stateDurationMs = CaptureStateStore.getStateDurationMs()

        val json = JSONObject()
            .put("ok", true)

            // 기본 식별 정보
            .put("deviceName", deviceName)
            .put("deviceId", deviceId)
            .put("model", Build.MODEL)
            .put("serial", getSerial())
            .put("ip", ip)
            .put("statusPort", statusPort)

            // RTSP 정보
            .put("rtspPort", rtspPort)
            .put("rtspUrl", rtspUrl)

            // 배터리 정보
            .put("battery", batteryLevel(context))
            .put("isCharging", isCharging(context))
            .put("chargingStatus", chargingStatus(context))
            .put("temperature", batteryTemperature(context))

            // Android / Agent 정보
            .put("androidVersion", Build.VERSION.RELEASE ?: "")
            .put("buildNumber", Build.DISPLAY ?: "")
            .put("agentVersion", AGENT_VERSION)

            // 송출 상태
            .put("streamState", CaptureStateStore.state)
            .put("streamGeneration", CaptureStateStore.streamGeneration)

            // 핵심 프레임 telemetry
            // WPF ZeroFrames 오판 방지용입니다.
            .put("encodedFrameCount", CaptureStateStore.encodedFrameCount)
            .put("lastFrameAtMs", CaptureStateStore.lastFrameAtMs)
            .put("frameAgeMs", frameAgeMs)
            .put("stateDurationMs", stateDurationMs)
            .put("rtspClientCount", CaptureStateStore.rtspClientCount)

            // 송출 사양
            .put("streamWidth", CaptureStateStore.width)
            .put("streamHeight", CaptureStateStore.height)
            .put("streamFps", CaptureStateStore.fps)
            .put("streamBitrate", CaptureStateStore.bitrate)

            // 현재 시각
            .put("timestamp", System.currentTimeMillis())

        Log.d(
            TAG,
            "[DeviceStatus] name=$deviceName state=${CaptureStateStore.state} " +
                    "frames=${CaptureStateStore.encodedFrameCount} " +
                    "lastFrameAtMs=${CaptureStateStore.lastFrameAtMs} " +
                    "frameAgeMs=$frameAgeMs " +
                    "rtspClients=${CaptureStateStore.rtspClientCount}"
        )

        return json.toString()
    }

    /**
     * 기기의 하드웨어 시리얼 번호를 가져옵니다.
     * Android 보안 정책에 따라 UNKNOWN일 수 있습니다.
     */
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
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
}