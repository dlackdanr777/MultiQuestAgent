package tdg.oculuswirelessadb.agent

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import tdg.oculuswirelessadb.CapturePermissionActivity
import tdg.oculuswirelessadb.IdleShieldActivity
import tdg.oculuswirelessadb.KeepAwakeActivity
import tdg.oculuswirelessadb.MainActivity
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


data class AgentCommandResult(
    val httpCode: Int,
    val body: JSONObject
)

object AgentCommandHandler {

    private const val TAG = "MQ-CMD"
    private const val DEFAULT_UNITY_ACTIVITY =
        "com.unity3d.player.UnityPlayerActivity"

    private const val MAX_RETRY_COUNT = 5
    private const val MAX_RETRY_INTERVAL_MS = 1_000
    private const val MAX_HOME_DELAY_MS = 3_000
    private const val ACTIVE_CAPTURE_TRANSITION_TIMEOUT_MS = 35_000L
    private const val APP_LAUNCH_VERIFY_TIMEOUT_MS = 35_000L
    private const val WAKE_VERIFY_DELAY_MS = 1_000L
    private const val TRANSIENT_SCREEN_WAKE_MS = 3_000L
    private const val ACTIVITY_DISPATCH_TIMEOUT_MS = 5_000L
    private const val APP_LAUNCH_GUARD_MS = 45_000L
    private const val FORCE_RESTART_STOP_COUNT = 2
    private const val FORCE_RESTART_STOP_INTERVAL_MS = 250L

    const val ACTION_STORYWING_STOP =
        "com.storywing.multiquest.ACTION_STOP"
    const val ACTION_STORYWING_COMMAND =
        "com.storywing.multiquest.ACTION_COMMAND"

    private val appCommandLock = ReentrantLock(true)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun handle(
        context: Context,
        path: String,
        body: String
    ): AgentCommandResult {
        return try {
            when (path) {
                "/command/launch" -> handleLaunch(context, body)
                "/command/stop" -> handleStop(context, body)
                "/command/stopAllStoryWing" ->
                    handleStopAllStoryWing(context, body)
                "/command/home" -> handleHome(context)

                "/command/restartCapture" ->
                    handleRestartCapture(context)
                "/command/startCaptureUi" ->
                    handleStartCaptureUi(context)

                "/command/appCommand" ->
                    handleAppCommand(context, body)

                "/command/wake" -> handleWake(context, body)
                "/command/keepAwake" ->
                    handleKeepAwake(context, body)
                "/command/showKeepAwake" ->
                    handleShowKeepAwake(context)
                "/command/hideKeepAwake" ->
                    handleHideKeepAwake()

                "/command/setDeviceName" ->
                    handleSetDeviceName(context, body)

                else -> error(
                    code = "unknown_command",
                    message = "Unknown command path: $path",
                    httpCode = 404
                )
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

    private fun handleSetDeviceName(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val newName = json.optString("deviceName", "").trim()

        if (newName.isBlank()) {
            return error(
                "bad_request",
                "deviceName is required",
                400
            )
        }

        AgentNameStore.setDeviceName(context, newName)
        return completed(
            JSONObject().put("deviceName", newName)
        )
    }

    private fun handleLaunch(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()

        val packageName = json.optString("packageName", "").trim()
        val activityName = json
            .optString("activityName", DEFAULT_UNITY_ACTIVITY)
            .trim()
        val forceRestart = json.optBoolean("forceRestart", false)
        val stopOthers = json.optBoolean("stopOthers", true)
        val stopRetryCount = clampRetryCount(
            json.optInt("stopRetryCount", 1)
        )
        val stopRetryIntervalMs = clampInterval(
            json.optInt("stopRetryIntervalMs", 100)
        )
        val extras = json.optJSONObject("extras")

        if (packageName.isBlank()) {
            return error(
                "bad_request",
                "packageName is required",
                400
            )
        }

        /*
         * 종료 상태에서 걸어 둔 무기한 자동 캡처 중지를 해제하고,
         * 검은 차단 화면 및 Agent 보조 UI를 제거한 뒤 StoryWing을 실행합니다.
         */
        AutoCapturePauseStore.resume(context)
        closeAgentUiBeforeExternalLaunch()

        AgentOperationStore.cancelPendingLaunches()

        if (forceRestart) {
            appCommandLock.withLock {
                repeat(FORCE_RESTART_STOP_COUNT) { index ->
                    sendStopToPackage(context, packageName)
                    if (index < FORCE_RESTART_STOP_COUNT - 1) {
                        SystemClock.sleep(FORCE_RESTART_STOP_INTERVAL_MS)
                    }
                }
            }
        }

        // 이전 앱의 CLOSED 방송 처리가 끝난 뒤 새 실행 보호 구간을 시작합니다.
        AppLaunchGuard.begin(packageName, APP_LAUNCH_GUARD_MS)

        val operationId = AgentOperationStore.begin(
            type = AgentOperationStore.TYPE_LAUNCH,
            target = packageName,
            reason = if (forceRestart) "HTTP_LAUNCH_FORCE_RESTART" else "HTTP_LAUNCH"
        )

        LastLaunchedAppStore.save(
            context = context,
            packageName = packageName,
            activityName = activityName,
            extrasJson = extras?.toString()
        )

        val launched = appCommandLock.withLock {
            if (stopOthers) {
                repeat(stopRetryCount) { retryIndex ->
                    for (pkg in STORYWING_PACKAGES) {
                        if (!pkg.equals(packageName, ignoreCase = true)) {
                            sendStopToPackage(context, pkg)
                        }
                    }

                    if (retryIndex < stopRetryCount - 1) {
                        SystemClock.sleep(stopRetryIntervalMs.toLong())
                    }
                }
            }

            launchApp(
                context = context,
                packageName = packageName,
                activityName = activityName,
                extras = extras,
                forceRestart = forceRestart
            )
        }

        if (!launched) {
            AppLaunchGuard.clearFor(packageName)
            AgentOperationStore.fail(
                operationId,
                "activity_launch_dispatch_failed",
                retryable = true
            )
            LastLaunchedAppStore.markClosed(
                context = context,
                packageName = packageName,
                reason = "ACTIVITY_LAUNCH_DISPATCH_FAILED"
            )

            return error(
                code = "launch_failed",
                message = "Could not dispatch activity launch",
                httpCode = 500,
                operationId = operationId,
                retryable = true
            )
        }

        AgentOperationStore.markDispatched(
            operationId,
            "activity launch dispatched; waiting APP_STATE_ACTIVE"
        )
        scheduleLaunchVerification(
            context = context.applicationContext,
            operationId = operationId,
            packageName = packageName
        )

        return accepted(
            operationId = operationId,
            extra = JSONObject()
                .put("packageName", packageName)
                .put("verification", "APP_STATE_ACTIVE")
                .put("timeoutMs", APP_LAUNCH_VERIFY_TIMEOUT_MS)
        )
    }

    private fun launchApp(
        context: Context,
        packageName: String,
        activityName: String,
        extras: JSONObject?,
        forceRestart: Boolean
    ): Boolean {
        val explicitIntent = Intent().apply {
            if (activityName.isNotBlank()) {
                setClassName(packageName, activityName)
            } else {
                setPackage(packageName)
            }

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

            if (forceRestart) {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            putJsonExtras(this, extras)
        }

        if (runActivityStartOnMain(context, explicitIntent)) {
            return true
        }

        val fallbackIntent = try {
            context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (forceRestart) {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    putJsonExtras(this, extras)
                }
        } catch (e: Exception) {
            Log.e(TAG, "getLaunchIntentForPackage failed", e)
            null
        } ?: return false

        return runActivityStartOnMain(context, fallbackIntent)
    }

    private fun handleStop(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val packageName = json.optString("packageName", "").trim()

        if (packageName.isBlank()) {
            return error(
                "bad_request",
                "packageName is required",
                400
            )
        }

        val retryCount = clampRetryCount(
            json.optInt("retryCount", 3)
        )
        val retryIntervalMs = clampInterval(
            json.optInt("retryIntervalMs", 150)
        )

        /*
         * 기본값은 true입니다.
         * 기존 WPF가 goHome=true를 보내더라도 disableUi=true가 우선되어
         * Quest Library/Home 대신 검은 차단 화면을 유지합니다.
         */
        val disableUi = json.optBoolean("disableUi", true)
        val goHomeRequested = json.optBoolean("goHome", false)
        val goHomeDelayMs = clampHomeDelay(
            json.optInt("goHomeDelayMs", 250)
        )

        AgentOperationStore.cancelPendingLaunchForPackage(packageName)
        AppLaunchGuard.clearFor(packageName)

        val operationId = AgentOperationStore.begin(
            AgentOperationStore.TYPE_STOP,
            packageName,
            "HTTP_STOP"
        )

        var idleShieldShown = false

        if (disableUi) {
            AutoCapturePauseStore.pauseIndefinitely(
                context,
                "APP_STOP_UI_DISABLED"
            )

            /*
             * 앱이 닫힌 다음 Shield를 띄우면 Library가 순간적으로 보일 수 있으므로
             * 먼저 검은 화면을 전면에 올린 뒤 StoryWing 종료 방송을 보냅니다.
             */
            idleShieldShown = showIdleShieldAndDisableAgentUi(
                context,
                "HTTP_STOP:$packageName"
            )
        }

        appCommandLock.withLock {
            LastLaunchedAppStore.markClosed(
                context,
                packageName,
                "HTTP_STOP"
            )

            repeat(retryCount) { retryIndex ->
                sendStopToPackage(context, packageName)

                if (retryIndex < retryCount - 1) {
                    SystemClock.sleep(retryIntervalMs.toLong())
                }
            }

            if (!disableUi && goHomeRequested) {
                SystemClock.sleep(goHomeDelayMs.toLong())
                goHome(context)
            }
        }

        AgentOperationStore.complete(
            operationId,
            "stop broadcast sequence completed"
        )

        return completed(
            JSONObject()
                .put("operationId", operationId)
                .put("packageName", packageName)
                .put("retryCount", retryCount)
                .put("uiDisabled", disableUi)
                .put("idleShieldShown", idleShieldShown)
                .put("goHome", !disableUi && goHomeRequested)
        )
    }

    private fun handleStopAllStoryWing(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()

        val retryCount = clampRetryCount(
            json.optInt("retryCount", 3)
        )
        val retryIntervalMs = clampInterval(
            json.optInt("retryIntervalMs", 150)
        )
        val disableUi = json.optBoolean("disableUi", true)
        val goHomeRequested = json.optBoolean("goHome", false)
        val goHomeDelayMs = clampHomeDelay(
            json.optInt("goHomeDelayMs", 600)
        )

        AgentOperationStore.cancelPendingLaunches(
            "launch_cancelled_by_stop_all"
        )
        AppLaunchGuard.clear()

        val operationId = AgentOperationStore.begin(
            AgentOperationStore.TYPE_STOP_ALL,
            reason = "HTTP_STOP_ALL"
        )

        var idleShieldShown = false

        if (disableUi) {
            AutoCapturePauseStore.pauseIndefinitely(
                context,
                "STOP_ALL_UI_DISABLED"
            )

            idleShieldShown = showIdleShieldAndDisableAgentUi(
                context,
                "HTTP_STOP_ALL"
            )
        }

        appCommandLock.withLock {
            LastLaunchedAppStore.clear(context)

            repeat(retryCount) { retryIndex ->
                for (pkg in STORYWING_PACKAGES) {
                    sendStopToPackage(context, pkg)
                }

                if (retryIndex < retryCount - 1) {
                    SystemClock.sleep(retryIntervalMs.toLong())
                }
            }

            if (!disableUi && goHomeRequested) {
                SystemClock.sleep(goHomeDelayMs.toLong())
                goHome(context)
            }
        }

        AgentOperationStore.complete(
            operationId,
            "all stop broadcast sequences completed"
        )

        return completed(
            JSONObject()
                .put("operationId", operationId)
                .put("retryCount", retryCount)
                .put("uiDisabled", disableUi)
                .put("idleShieldShown", idleShieldShown)
                .put("goHome", !disableUi && goHomeRequested)
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

    /**
     * 종료 시에는 시스템 Home/Library로 보내지 않고 조작 불가능한 검은
     * IdleShieldActivity를 전면에 둡니다. 나머지 Agent UI는 모두 닫습니다.
     */
    private fun showIdleShieldAndDisableAgentUi(
        context: Context,
        reason: String
    ): Boolean {
        val dispatched = runActivityStartOnMain(
            context,
            IdleShieldActivity.createIntent(
                context,
                reason
            )
        )

        MainActivity.finishCurrent()
        CapturePermissionActivity.finishCurrentForUiDisable()
        KeepAwakeActivity.finishCurrent()

        if (!dispatched) {
            return false
        }

        /*
         * startActivity() 접수와 실제 onCreate()는 다를 수 있으므로
         * 최대 1.5초 동안 Shield의 실제 표시 상태를 확인합니다.
         */
        val verifyUntil =
            SystemClock.elapsedRealtime() + 1_500L

        while (
            SystemClock.elapsedRealtime() < verifyUntil &&
            !AgentUiState.isIdleShieldVisible
        ) {
            SystemClock.sleep(50L)
        }

        return AgentUiState.isIdleShieldVisible
    }

    /**
     * 다음 StoryWing 앱 실행 전에 종료용 검은 화면과 Agent UI를 정리합니다.
     * StoryWing Activity가 마지막으로 전면 전환되도록 하는 처리입니다.
     */
    private fun closeAgentUiBeforeExternalLaunch() {
        IdleShieldActivity.finishCurrent()
        MainActivity.finishCurrent()
        CapturePermissionActivity.finishCurrentForUiDisable()
        KeepAwakeActivity.finishCurrent()
    }

    private fun handleHome(context: Context): AgentCommandResult {
        val ok = appCommandLock.withLock {
            goHome(context)
        }

        return if (ok) {
            completed()
        } else {
            error("home_failed", "Could not open Home", 500)
        }
    }

    private fun goHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runActivityStartOnMain(context, intent)
    }

    private fun canOpenCaptureUi(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive && !(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            pm.isDeviceIdleMode
                    )
        } catch (_: Exception) {
            false
        }
    }

    private fun handleRestartCapture(
        context: Context
    ): AgentCommandResult {
        if (!canOpenCaptureUi(context)) {
            return retryableError(
                code = "device_asleep",
                message = "Device is not interactive",
                retryAfterMs = 5_000
            )
        }

        val operationId = AgentService.requestCaptureRestart(
            context,
            "HTTP_RESTART_CAPTURE"
        )

        return accepted(operationId)
    }

    private fun handleStartCaptureUi(
        context: Context
    ): AgentCommandResult {
        if (!canOpenCaptureUi(context)) {
            return retryableError(
                code = "device_asleep",
                message = "Device is not interactive",
                retryAfterMs = 5_000
            )
        }

        val snapshot = CaptureStateStore.snapshot()

        if (
            CaptureService.isServiceAlive &&
            snapshot.state == CaptureStateStore.STATE_RUNNING &&
            snapshot.encodedFrameCount > 0L &&
            snapshot.frameAgeMs in 0..20_000L
        ) {
            return completed(
                JSONObject()
                    .put("alreadyRunning", true)
                    .put("streamGeneration", snapshot.streamGeneration)
            )
        }

        if (
            (snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION ||
                    snapshot.state == CaptureStateStore.STATE_STARTING) &&
            snapshot.stateDurationMs < ACTIVE_CAPTURE_TRANSITION_TIMEOUT_MS
        ) {
            val activeOperationId = snapshot.activeOperationId.ifBlank {
                AgentOperationStore.latestPendingCaptureOperationId()
            }

            if (activeOperationId.isBlank()) {
                return retryableError(
                    code = "capture_transition_without_operation",
                    message = "Capture is already transitioning but has no operationId",
                    retryAfterMs = 2_000
                )
            }

            return AgentCommandResult(
                202,
                JSONObject()
                    .put("ok", true)
                    .put("accepted", true)
                    .put("completed", false)
                    .put("alreadyInProgress", true)
                    .put("operationId", activeOperationId)
                    .put("state", snapshot.state)
            )
        }

        val operationId = AgentService.requestCaptureStart(
            context,
            "HTTP_START_CAPTURE_UI"
        )

        return accepted(operationId)
    }

    private fun handleWake(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val keepAwake = json.optBoolean("keepAwake", true)

        if (keepAwake) {
            KeepAliveController.acquire(context)
        }

        val interactive = canOpenCaptureUi(context)
        if (interactive) {
            return completed(
                JSONObject()
                    .put("activityShown", false)
                    .put("wakeLockPulsed", false)
                    .put("alreadyInteractive", true)
                    .put("keepAliveEnabled", KeepAliveController.isEnabled)
            )
        }

        val operationId = AgentOperationStore.begin(
            AgentOperationStore.TYPE_WAKE,
            reason = "HTTP_WAKE"
        )

        // 일반 앱의 백그라운드 Activity 시작이 차단되는 경우를 대비한
        // Quest 전용 보조 경로입니다. 3초 타임아웃으로 자동 해제됩니다.
        val wakeLockPulsed = pulseScreenWakeLock(context)
        val shown = openKeepAwakeActivity(
            context,
            "HTTP_WAKE",
            operationId
        )

        if (!shown && !wakeLockPulsed) {
            AgentOperationStore.fail(
                operationId,
                "wake_dispatch_failed",
                retryable = true
            )
            return retryableError(
                "wake_dispatch_failed",
                "Could not dispatch a wake mechanism",
                2_000,
                operationId
            )
        }

        AgentOperationStore.markDispatched(
            operationId,
            "wake dispatched; activity=$shown wakeLock=$wakeLockPulsed"
        )
        scheduleWakeVerification(operationId, context.applicationContext)

        return accepted(
            operationId,
            JSONObject()
                .put("activityShown", shown)
                .put("wakeLockPulsed", wakeLockPulsed)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
        )
    }

    private fun handleKeepAwake(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val enabled = json.optBoolean("enabled", true)

        if (enabled) {
            KeepAliveController.acquire(context)
        } else {
            KeepAwakeActivity.finishCurrent()
            KeepAliveController.release()
        }

        return completed(
            JSONObject()
                .put("enabled", enabled)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
                .put("cpuWakeLockHeld", KeepAliveController.isCpuWakeLockHeld)
                .put("wifiLockHeld", KeepAliveController.isWifiLockHeld)
        )
    }

    private fun handleShowKeepAwake(
        context: Context
    ): AgentCommandResult {
        KeepAliveController.acquire(context)

        val operationId = AgentOperationStore.begin(
            AgentOperationStore.TYPE_WAKE,
            reason = "HTTP_SHOW_KEEP_AWAKE"
        )

        val shown = openKeepAwakeActivity(
            context,
            "HTTP_SHOW_KEEP_AWAKE",
            operationId
        )

        if (!shown) {
            AgentOperationStore.fail(
                operationId,
                "keep_awake_activity_start_failed",
                retryable = true
            )
            return retryableError(
                "keep_awake_activity_start_failed",
                "Could not dispatch KeepAwakeActivity",
                2_000,
                operationId
            )
        }

        AgentOperationStore.markDispatched(
            operationId,
            "KeepAwakeActivity dispatched"
        )

        return accepted(
            operationId,
            JSONObject()
                .put("activityShown", true)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
        )
    }

    private fun handleHideKeepAwake(): AgentCommandResult {
        KeepAwakeActivity.finishCurrent()
        return completed(JSONObject().put("hidden", true))
    }

    private fun openKeepAwakeActivity(
        context: Context,
        reason: String,
        operationId: String
    ): Boolean {
        // KeepAwakeActivity에 createIntent()가 없어도 컴파일되도록
        // 여기에서 Intent를 직접 생성합니다.
        val intent = Intent(context, KeepAwakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

            putExtra("reason", reason)
            putExtra("operationId", operationId)
        }

        return runActivityStartOnMain(context, intent)
    }

    private fun handleAppCommand(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()

        val packageName = json.optString("packageName", "").trim()
        val command = json.optString("command", "").trim()
        val payload = json.optJSONObject("payload")
            ?: json.optJSONObject("args")
            ?: JSONObject()

        if (packageName.isBlank()) {
            return error(
                "bad_request",
                "packageName is required",
                400
            )
        }

        if (command.isBlank()) {
            return error(
                "bad_request",
                "command is required",
                400
            )
        }

        return try {
            val intent = Intent(ACTION_STORYWING_COMMAND).apply {
                setPackage(packageName)
                putExtra("command", command)
                putExtra("payload", payload.toString())
            }

            context.sendBroadcast(intent)
            completed(
                JSONObject()
                    .put("packageName", packageName)
                    .put("command", command)
            )
        } catch (e: Exception) {
            error(
                "app_command_failed",
                e.message ?: e.javaClass.simpleName,
                500
            )
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun pulseScreenWakeLock(context: Context): Boolean {
        return try {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.isInteractive) return true

            val wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "MultiQuest:TransientScreenWake"
            )
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(TRANSIENT_SCREEN_WAKE_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Transient screen wake lock failed", e)
            false
        }
    }

    private fun scheduleWakeVerification(
        operationId: String,
        context: Context
    ) {
        mainHandler.postDelayed({
            if (!AgentOperationStore.isPending(operationId)) {
                return@postDelayed
            }

            val power = try {
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            } catch (_: Exception) {
                null
            }

            if (power?.isInteractive == true) {
                AgentOperationStore.complete(
                    operationId,
                    "device_interactive_after_wake_dispatch"
                )
            } else {
                AgentOperationStore.fail(
                    operationId,
                    "device_still_not_interactive",
                    retryable = true
                )
            }
        }, WAKE_VERIFY_DELAY_MS)
    }

    private fun scheduleLaunchVerification(
        context: Context,
        operationId: String,
        packageName: String
    ) {
        mainHandler.postDelayed({
            if (AgentOperationStore.isPending(operationId)) {
                AppLaunchGuard.clearFor(packageName)
                AgentOperationStore.fail(
                    operationId,
                    "app_active_ack_timeout",
                    retryable = true,
                    details = JSONObject()
                        .put("packageName", packageName)
                        .put("expectedAck", "APP_STATE_ACTIVE")
                        .put("timeoutMs", APP_LAUNCH_VERIFY_TIMEOUT_MS)
                )
                LastLaunchedAppStore.markClosed(
                    context = context,
                    packageName = packageName,
                    reason = "APP_ACTIVE_ACK_TIMEOUT"
                )
            }
        }, APP_LAUNCH_VERIFY_TIMEOUT_MS)
    }

    private fun runActivityStartOnMain(
        context: Context,
        intent: Intent,
        timeoutMs: Long = ACTIVITY_DISPATCH_TIMEOUT_MS
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return startActivityCompat(context, intent)
        }

        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        mainHandler.post {
            try {
                result.set(startActivityCompat(context, intent))
            } finally {
                latch.countDown()
            }
        }

        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun startActivityCompat(
        context: Context,
        intent: Intent
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val requestCode = (
                    intent.component?.flattenToShortString().orEmpty() +
                            intent.action.orEmpty() +
                            SystemClock.elapsedRealtime().toString()
                    ).hashCode()

            val pendingIntent = try {
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    Intent(intent),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (e: Exception) {
                Log.w(TAG, "PendingIntent creation failed; direct launch fallback", e)
                null
            }

            if (pendingIntent != null) {
                try {
                    val options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                        .toBundle()

                    pendingIntent.send(
                        context,
                        0,
                        null,
                        null,
                        null,
                        null,
                        options
                    )
                    pendingIntent.cancel()
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "PendingIntent activity launch failed; direct launch fallback", e)
                    try {
                        pendingIntent.cancel()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed: $intent", e)
            false
        }
    }

    private fun parseBody(body: String): JSONObject? {
        if (body.isBlank()) return JSONObject()
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
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
            when (val value = extras.opt(key)) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> {
                    val asLong = value.toLong()
                    if (value == asLong.toDouble()) {
                        intent.putExtra(key, asLong)
                    } else {
                        intent.putExtra(key, value)
                    }
                }
                else -> intent.putExtra(key, value?.toString().orEmpty())
            }
        }
    }

    private fun clampRetryCount(value: Int): Int =
        value.coerceIn(1, MAX_RETRY_COUNT)

    private fun clampInterval(value: Int): Int =
        value.coerceIn(50, MAX_RETRY_INTERVAL_MS)

    private fun clampHomeDelay(value: Int): Int =
        value.coerceIn(0, MAX_HOME_DELAY_MS)

    private fun invalidJson(): AgentCommandResult =
        error("invalid_json", "Request body is not valid JSON", 400)

    private fun accepted(
        operationId: String,
        extra: JSONObject = JSONObject()
    ): AgentCommandResult {
        val body = JSONObject()
            .put("ok", true)
            .put("accepted", true)
            .put("completed", false)
            .put("retryable", false)
            .put("operationId", operationId)

        merge(body, extra)
        return AgentCommandResult(202, body)
    }

    private fun completed(
        extra: JSONObject = JSONObject()
    ): AgentCommandResult {
        val body = JSONObject()
            .put("ok", true)
            .put("accepted", true)
            .put("completed", true)
            .put("retryable", false)

        merge(body, extra)
        return AgentCommandResult(200, body)
    }

    private fun retryableError(
        code: String,
        message: String,
        retryAfterMs: Int,
        operationId: String = ""
    ): AgentCommandResult {
        return AgentCommandResult(
            409,
            JSONObject()
                .put("ok", false)
                .put("accepted", false)
                .put("completed", false)
                .put("retryable", true)
                .put("retryAfterMs", retryAfterMs)
                .put("operationId", operationId)
                .put("error", code)
                .put("message", message)
        )
    }

    private fun error(
        code: String,
        message: String,
        httpCode: Int,
        operationId: String = "",
        retryable: Boolean = false
    ): AgentCommandResult {
        return AgentCommandResult(
            httpCode,
            JSONObject()
                .put("ok", false)
                .put("accepted", false)
                .put("completed", false)
                .put("retryable", retryable)
                .put("operationId", operationId)
                .put("error", code)
                .put("message", message)
        )
    }

    private fun merge(
        destination: JSONObject,
        source: JSONObject
    ) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            destination.put(key, source.opt(key))
        }
    }
}