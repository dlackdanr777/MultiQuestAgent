package tdg.oculuswirelessadb.agent

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tdg.oculuswirelessadb.CapturePermissionActivity
import tdg.oculuswirelessadb.KeepAwakeActivity
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore

data class AgentCommandResult(
    val httpCode: Int,
    val body: JSONObject
)

object AgentCommandHandler {

    private const val TAG = "MQ-CMD"

    private var loggingJob: Job? = null

    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val DEFAULT_UNITY_ACTIVITY = "com.unity3d.player.UnityPlayerActivity"

    const val ACTION_STORYWING_STOP = "com.storywing.multiquest.ACTION_STOP"
    const val ACTION_STORYWING_COMMAND = "com.storywing.multiquest.ACTION_COMMAND"

    private val STORYWING_PACKAGES = listOf(
        "com.StoryWing.EnglishTown",
        "com.StoryWing.AlphabatApp",
        "com.StoryWing.Ocean_Adventure",
        "com.StoryWing.OceanAdventure",
        "com.StoryWing.Korea_History",
        "com.StoryWing.KoreaHistory2",
        "com.StoryWing.Solar_System",
        "com.StoryWing.SpaceApp",
        "com.StoryWing.Fire_Safety",
        "com.StoryWing.FirepreventionApp",
        "com.StoryWing.Jurassic",
        "com.StoryWing.BrainPop",
        "com.StoryWing.XR_BrainTraining",
        "com.StoryWing.SmartFarm",
        "com.StoryWing.GyeongjuMR",
        "com.StoryWing.Museum",
        "com.StoryWing.XR_Museum",
        "com.StoryWing.World_Travel",
        "com.StoryWing.XR_WorldMap",
        "com.StoryWing.OceanAdventure_ViewVer",
        "com.StoryWing.SpaceAppTest",
        "com.StoryWing.XR_Coding",
        "com.StoryWing.Storywing_Class",
        "com.StoryWing.StorywingClass",
        "com.StoryWing.App1",
        "com.StoryWing.App2",
        "com.StoryWing.App3",
        "com.StoryWing.App4",
        "com.StoryWing.App5",
        "com.StoryWing.App6",
        "com.StoryWing.App7",
        "com.StoryWing.App8",
        "com.StoryWing.App9",
        "com.StoryWing.App10",
        "com.StoryWing.App11",
        "com.StoryWing.App12",
        "com.StoryWing.App13",
        "com.StoryWing.App14",
        "com.StoryWing.App15",
        "com.StoryWing.App16",
        "com.StoryWing.App17",
        "com.StoryWing.App18",
        "com.StoryWing.App19",
        "com.StoryWing.App20"
    )

    fun startLogging(context: Context) {
        if (loggingJob != null) return

        loggingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val savedName = AgentNameStore.getDeviceName(context)
                val currentDeviceName =
                    if (savedName.isNotBlank()) savedName else Build.MODEL

                Log.d(TAG, "[DeviceStatus] Current Device Name: $currentDeviceName")

                delay(10_000L)
            }
        }
    }

    fun handle(
        context: Context,
        path: String,
        body: String
    ): AgentCommandResult {
        startLogging(context)

        return try {
            when (path) {
                "/status" -> handleStatus(context)

                "/command/launch" -> handleLaunch(context, body)
                "/command/stop" -> handleStop(context, body)
                "/command/stopAllStoryWing" -> handleStopAllStoryWing(context, body)
                "/command/home" -> handleHome(context)

                "/command/restartCapture" -> handleRestartCapture(context)
                "/command/startCaptureUi" -> handleStartCaptureUi(context)

                "/command/appCommand" -> handleAppCommand(context, body)

                "/command/wake" -> handleWake(context, body)
                "/command/keepAwake" -> handleKeepAwake(context, body)
                "/command/showKeepAwake" -> handleShowKeepAwake(context)
                "/command/hideKeepAwake" -> handleHideKeepAwake()

                "/command/setDeviceName" -> handleSetDeviceName(context, body)

                else -> {
                    error(
                        code = "unknown_command",
                        message = "Unknown command path: $path",
                        httpCode = 404
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "command failed: $path", e)

            error(
                code = "exception",
                message = e.message ?: e.javaClass.simpleName,
                httpCode = 500
            )
        }
    }

    private fun handleStatus(context: Context): AgentCommandResult {
        val deviceName = AgentNameStore.getDeviceName(context).let {
            if (it.isBlank()) {
                "Quest3_${StableDeviceId.get(context).take(4)}"
            } else {
                it
            }
        }

        val result = JSONObject()
            .put("ok", true)
            .put("deviceName", deviceName)
            .put("deviceId", StableDeviceId.get(context))
            .put("battery", getBatteryLevel(context))
            .put("streamState", CaptureStateStore.state)
            .put("rtspUrl", CaptureStateStore.rtspUrl)
            .put("streamGeneration", CaptureStateStore.streamGeneration)
            .put("encodedFrameCount", CaptureStateStore.encodedFrameCount)
            .put("lastFrameAtMs", CaptureStateStore.lastFrameAtMs)
            .put("frameAgeMs", CaptureStateStore.getFrameAgeMs())
            .put("stateDurationMs", CaptureStateStore.getStateDurationMs())
            .put("rtspClientCount", CaptureStateStore.rtspClientCount)
            .put("model", Build.MODEL)
            .put("keepAliveEnabled", KeepAliveController.isEnabled)
            .put("cpuWakeLockHeld", KeepAliveController.isCpuWakeLockHeld)
            .put("wifiLockHeld", KeepAliveController.isWifiLockHeld)
            .put("lastApp", LastLaunchedAppStore.toDebugJson(context))
            .put("timestamp", System.currentTimeMillis())

        return AgentCommandResult(200, result)
    }

    private fun handleSetDeviceName(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body)
        val newName = json.optString("deviceName", "").trim()

        if (newName.isBlank()) {
            return error(
                code = "bad_request",
                message = "deviceName is required",
                httpCode = 400
            )
        }

        AgentNameStore.setDeviceName(context, newName)

        return AgentCommandResult(
            200,
            JSONObject()
                .put("ok", true)
                .put("deviceName", newName)
        )
    }

    private fun handleLaunch(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body)

        val packageName = json.optString("packageName", "").trim()
        val activityName =
            json.optString("activityName", DEFAULT_UNITY_ACTIVITY).trim()
        val forceRestart = json.optBoolean("forceRestart", false)
        val extras = json.optJSONObject("extras")

        if (packageName.isBlank()) {
            return error(
                code = "bad_request",
                message = "packageName is required",
                httpCode = 400
            )
        }

        LastLaunchedAppStore.save(
            context = context,
            packageName = packageName,
            activityName = activityName,
            extrasJson = extras?.toString()
        )

        for (pkg in STORYWING_PACKAGES) {
            if (!pkg.equals(packageName, ignoreCase = true)) {
                sendStopToPackage(context, pkg)
            }
        }

        val launched = launchApp(
            context = context,
            packageName = packageName,
            activityName = activityName,
            extras = extras,
            forceRestart = forceRestart
        )

        return AgentCommandResult(
            if (launched) 200 else 500,
            JSONObject()
                .put("ok", launched)
                .put("packageName", packageName)
        )
    }

    private fun launchApp(
        context: Context,
        packageName: String,
        activityName: String,
        extras: JSONObject?,
        forceRestart: Boolean
    ): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(packageName, activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                if (forceRestart) {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }

                putJsonExtras(this, extras)
            }

            context.startActivity(intent)

            true
        } catch (e: Exception) {
            Log.w(TAG, "launch specific activity failed. package=$packageName", e)

            try {
                val launchIntent =
                    context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return false

                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putJsonExtras(launchIntent, extras)

                context.startActivity(launchIntent)

                true
            } catch (e2: Exception) {
                Log.e(TAG, "launch package failed. package=$packageName", e2)
                false
            }
        }
    }

    private fun handleStop(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body)

        val packageName = json.optString("packageName", "").trim()

        if (packageName.isBlank()) {
            return error(
                code = "bad_request",
                message = "packageName is required",
                httpCode = 400
            )
        }

        LastLaunchedAppStore.markClosed(
            context = context,
            packageName = packageName,
            reason = "HTTP_STOP"
        )

        commandScope.launch {
            repeat(5) {
                sendStopToPackage(context, packageName)
                delay(300L)
            }
        }

        return AgentCommandResult(
            200,
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
        )
    }

    private fun handleStopAllStoryWing(
        context: Context,
        body: String
    ): AgentCommandResult {
        @Suppress("UNUSED_VARIABLE")
        val json = parseBody(body)

        LastLaunchedAppStore.clear(context)

        commandScope.launch {
            repeat(5) {
                for (pkg in STORYWING_PACKAGES) {
                    sendStopToPackage(context, pkg)
                }

                delay(300L)
            }

            goHome(context)
        }

        return AgentCommandResult(
            200,
            JSONObject().put("ok", true)
        )
    }

    private fun sendStopToPackage(
        context: Context,
        packageName: String
    ): Boolean {
        return try {
            val intent = Intent(ACTION_STORYWING_STOP).apply {
                setPackage(packageName)
                putExtra(
                    "payload",
                    JSONObject()
                        .put("command", "stop")
                        .toString()
                )
            }

            context.sendBroadcast(intent)

            true
        } catch (e: Exception) {
            Log.e(TAG, "sendStopToPackage failed. package=$packageName", e)
            false
        }
    }

    private fun handleHome(context: Context): AgentCommandResult {
        val ok = goHome(context)

        return AgentCommandResult(
            if (ok) 200 else 500,
            JSONObject().put("ok", ok)
        )
    }

    private fun goHome(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            true
        } catch (e: Exception) {
            Log.e(TAG, "goHome failed", e)
            false
        }
    }

    private fun canOpenCaptureUi(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            pm.isInteractive &&
                    !(
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                    pm.isDeviceIdleMode
                            )
        } catch (_: Exception) {
            false
        }
    }

    private fun handleRestartCapture(context: Context): AgentCommandResult {
        if (!canOpenCaptureUi(context)) {
            return AgentCommandResult(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("ignored", true)
                    .put("reason", "device_asleep")
            )
        }

        CaptureService.stop(context)
        CaptureStateStore.clearToIdle()

        val opened = openCapturePermissionActivity(
            context = context,
            reason = "HTTP_RESTART_CAPTURE"
        )

        return AgentCommandResult(
            if (opened) 200 else 500,
            JSONObject().put("ok", opened)
        )
    }

    private fun handleStartCaptureUi(context: Context): AgentCommandResult {
        if (!canOpenCaptureUi(context)) {
            return AgentCommandResult(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("ignored", true)
                    .put("reason", "device_asleep")
            )
        }

        val opened = openCapturePermissionActivity(
            context = context,
            reason = "HTTP_START_CAPTURE_UI"
        )

        return AgentCommandResult(
            if (opened) 200 else 500,
            JSONObject().put("ok", opened)
        )
    }

    private fun handleWake(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body)
        val keepAwake = json.optBoolean("keepAwake", true)

        if (keepAwake) {
            KeepAliveController.acquire(context)
        }

        val shown = openKeepAwakeActivity(
            context = context,
            reason = "HTTP_WAKE"
        )

        return AgentCommandResult(
            200,
            JSONObject()
                .put("ok", true)
                .put("keepAwake", keepAwake)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
                .put("cpuWakeLockHeld", KeepAliveController.isCpuWakeLockHeld)
                .put("wifiLockHeld", KeepAliveController.isWifiLockHeld)
                .put("activityShown", shown)
        )
    }

    private fun handleKeepAwake(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body)
        val enabled = json.optBoolean("enabled", true)

        if (enabled) {
            KeepAliveController.acquire(context)
        } else {
            KeepAwakeActivity.finishCurrent()
            KeepAliveController.release()
        }

        return AgentCommandResult(
            200,
            JSONObject()
                .put("ok", true)
                .put("enabled", enabled)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
                .put("cpuWakeLockHeld", KeepAliveController.isCpuWakeLockHeld)
                .put("wifiLockHeld", KeepAliveController.isWifiLockHeld)
        )
    }

    private fun handleShowKeepAwake(context: Context): AgentCommandResult {
        KeepAliveController.acquire(context)

        val shown = openKeepAwakeActivity(
            context = context,
            reason = "HTTP_SHOW_KEEP_AWAKE"
        )

        return AgentCommandResult(
            200,
            JSONObject()
                .put("ok", true)
                .put("activityShown", shown)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
                .put("cpuWakeLockHeld", KeepAliveController.isCpuWakeLockHeld)
                .put("wifiLockHeld", KeepAliveController.isWifiLockHeld)
        )
    }

    private fun handleHideKeepAwake(): AgentCommandResult {
        KeepAwakeActivity.finishCurrent()

        return AgentCommandResult(
            200,
            JSONObject()
                .put("ok", true)
                .put("hidden", true)
        )
    }

    private fun openKeepAwakeActivity(
        context: Context,
        reason: String
    ): Boolean {
        val intent = Intent(context, KeepAwakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("reason", reason)
        }

        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                .toBundle()
        } else {
            null
        }

        return try {
            Log.w(TAG, "KeepAwakeActivity open. reason=$reason")
            context.startActivity(intent, options)
            true
        } catch (e: Exception) {
            Log.e(TAG, "openKeepAwakeActivity failed. reason=$reason", e)
            false
        }
    }

    private fun openCapturePermissionActivity(
        context: Context,
        reason: String
    ): Boolean {
        val intent = CapturePermissionActivity.createIntent(context, reason).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                .toBundle()
        } else {
            null
        }

        return try {
            context.startActivity(intent, options)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to open CapturePermissionActivity", e)
            false
        }
    }

    private fun handleAppCommand(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body)

        val packageName = json.optString("packageName", "").trim()
        val command = json.optString("command", "").trim()
        val payload = json.optJSONObject("payload")

        if (packageName.isBlank()) {
            return error(
                code = "bad_request",
                message = "packageName is required",
                httpCode = 400
            )
        }

        val intent = Intent(ACTION_STORYWING_COMMAND).apply {
            setPackage(packageName)
            putExtra("command", command)
            putExtra("payload", payload?.toString() ?: "{}")
        }

        context.sendBroadcast(intent)

        return AgentCommandResult(
            200,
            JSONObject().put("ok", true)
        )
    }

    private fun getBatteryLevel(context: Context): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level >= 0 && scale > 0) {
            level * 100 / scale
        } else {
            -1
        }
    }

    private fun parseBody(body: String): JSONObject {
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun putJsonExtras(
        intent: Intent,
        extras: JSONObject?
    ) {
        if (extras == null) return

        val keys = extras.keys()

        while (keys.hasNext()) {
            val key = keys.next()

            when (val value = extras.get(key)) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
    }

    private fun error(
        code: String,
        message: String,
        httpCode: Int
    ): AgentCommandResult {
        return AgentCommandResult(
            httpCode,
            JSONObject()
                .put("ok", false)
                .put("error", code)
                .put("message", message)
        )
    }
}