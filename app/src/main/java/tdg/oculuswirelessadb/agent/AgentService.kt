package tdg.oculuswirelessadb.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tdg.oculuswirelessadb.CapturePermissionActivity
import tdg.oculuswirelessadb.MainActivity
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore

class AgentService : Service() {

    companion object {
        const val ACTION_START = "tdg.oculuswirelessadb.agent.START"
        const val ACTION_STOP = "tdg.oculuswirelessadb.agent.STOP"
        const val ACTION_REQUEST_CAPTURE_START =
            "tdg.oculuswirelessadb.agent.REQUEST_CAPTURE_START"
        const val ACTION_REQUEST_CAPTURE_RESTART =
            "tdg.oculuswirelessadb.agent.REQUEST_CAPTURE_RESTART"

        private const val EXTRA_REASON = "reason"
        private const val EXTRA_OPERATION_ID = "operationId"

        private const val TAG = "MQ-Agent"
        private const val CHANNEL_ID = "multiquest_agent"
        private const val RECOVERY_CHANNEL_ID = "multiquest_recovery"
        private const val NOTIFICATION_ID = 24001

        const val RTSP_PORT = 8554

        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val RECOVERY_COOLDOWN_MS = 20_000L
        private const val WAITING_PERMISSION_TIMEOUT_MS = 35_000L
        private const val STARTING_TIMEOUT_MS = 30_000L
        private const val RUNNING_GRACE_MS = 20_000L
        private const val STALE_RUNNING_FRAME_MS = 20_000L
        private const val ACTIVITY_LAUNCH_VERIFY_MS = 3_000L

        private const val SERVICE_START_CHECK_DELAY_MS = 12_000L
        private const val SCREEN_ON_CHECK_DELAY_MS = 4_000L
        private const val IDLE_EXIT_CHECK_DELAY_MS = 5_000L
        private const val NETWORK_AVAILABLE_CHECK_DELAY_MS = 3_000L
        private const val ON_START_COMMAND_CHECK_DELAY_MS = 4_000L
        private const val APP_LAUNCH_GUARD_RECHECK_PADDING_MS = 500L

        private const val PRIORITY_AUTO = 10
        private const val PRIORITY_EVENT = 20
        private const val PRIORITY_EXPLICIT = 100

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, AgentService::class.java))
            }
        }

        fun requestCaptureStart(
            context: Context,
            reason: String
        ): String {
            return enqueueCaptureRequest(
                context = context,
                action = ACTION_REQUEST_CAPTURE_START,
                operationType = AgentOperationStore.TYPE_CAPTURE_START,
                reason = reason
            )
        }

        fun requestCaptureRestart(
            context: Context,
            reason: String
        ): String {
            return enqueueCaptureRequest(
                context = context,
                action = ACTION_REQUEST_CAPTURE_RESTART,
                operationType = AgentOperationStore.TYPE_CAPTURE_RESTART,
                reason = reason
            )
        }

        private fun enqueueCaptureRequest(
            context: Context,
            action: String,
            operationType: String,
            reason: String
        ): String {
            val operationId = AgentOperationStore.begin(
                type = operationType,
                reason = reason
            )

            val intent = Intent(context, AgentService::class.java).apply {
                this.action = action
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_OPERATION_ID, operationId)
            }

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AgentOperationStore.fail(
                    operationId,
                    e.message ?: "agent_service_start_failed",
                    retryable = true
                )
            }

            return operationId
        }
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recoveryMutex = Mutex()
    private val scheduleLock = Any()

    private lateinit var httpServer: SimpleStatusHttpServer
    private lateinit var nsdAdvertiser: AgentNsdAdvertiser

    private var statusPort: Int = 0
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingNsdReregisterRunnable: Runnable? = null

    @Volatile
    private var lastRecoveryAtElapsedMs: Long = 0L

    private var pendingRecoveryJob: Job? = null
    private var pendingRecoveryDueAtElapsedMs: Long = Long.MAX_VALUE
    private var pendingRecoveryPriority: Int = Int.MIN_VALUE
    private var pendingRecoveryOperationId: String = ""
    private var pendingRecoveryToken: Long = 0L

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "화면 꺼짐 감지 - 캡처를 즉시 끊지 않고 상태를 보존합니다")
                    KeepAliveController.acquire(applicationContext)

                    try {
                        LastLaunchedAppStore.prepareForSleep(
                            applicationContext,
                            "SCREEN_OFF"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "prepareForSleep failed", e)
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "화면 켜짐 감지 - 캡처 상태 확인 예약")
                    KeepAliveController.acquire(applicationContext)
                    scheduleRecovery(
                        reason = "SCREEN_ON",
                        delayMs = SCREEN_ON_CHECK_DELAY_MS,
                        priority = PRIORITY_EVENT
                    )
                }

                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isDeviceIdleMode) {
                        Log.i(TAG, "딥슬립 진입 감지 - 세션을 강제로 끊지 않습니다")
                    } else {
                        Log.i(TAG, "딥슬립 해제 감지 - 캡처 상태 확인 예약")
                        scheduleRecovery(
                            reason = "DEVICE_IDLE_EXIT",
                            delayMs = IDLE_EXIT_CHECK_DELAY_MS,
                            priority = PRIORITY_EVENT
                        )
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()
        startAsForeground("MultiQuest Agent 실행 중")

        KeepAliveController.acquire(applicationContext)
        acquireMulticastLock()

        try {
            httpServer = SimpleStatusHttpServer(
                applicationContext,
                RTSP_PORT,
                scope
            )
            statusPort = httpServer.start()
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server start failed", e)
            updateNotification(
                "HTTP 서버 시작 실패: ${e.javaClass.simpleName}",
                highPriority = true
            )
            stopSelf()
            return
        }

        nsdAdvertiser = AgentNsdAdvertiser(applicationContext)
        nsdAdvertiser.register(statusPort, RTSP_PORT)

        registerNetworkCallback()
        registerPowerReceiver()
        startWatchdog()

        scheduleRecovery(
            reason = "SERVICE_START",
            delayMs = SERVICE_START_CHECK_DELAY_MS,
            priority = PRIORITY_AUTO
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REQUEST_CAPTURE_START -> {
                AutoCapturePauseStore.resume(applicationContext)
                scheduleRecovery(
                    reason = intent.getStringExtra(EXTRA_REASON)
                        .orEmpty()
                        .ifBlank { "EXPLICIT_CAPTURE_START" },
                    delayMs = 0L,
                    operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty(),
                    restart = false,
                    priority = PRIORITY_EXPLICIT,
                    addJitter = false
                )
            }

            ACTION_REQUEST_CAPTURE_RESTART -> {
                AutoCapturePauseStore.resume(applicationContext)
                scheduleRecovery(
                    reason = intent.getStringExtra(EXTRA_REASON)
                        .orEmpty()
                        .ifBlank { "EXPLICIT_CAPTURE_RESTART" },
                    delayMs = 0L,
                    operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty(),
                    restart = true,
                    priority = PRIORITY_EXPLICIT,
                    addJitter = false
                )
            }

            else -> {
                KeepAliveController.acquire(applicationContext)

                if (!isCaptureHealthy()) {
                    scheduleRecovery(
                        reason = "ON_START_COMMAND",
                        delayMs = ON_START_COMMAND_CHECK_DELAY_MS,
                        priority = PRIORITY_AUTO
                    )
                }
            }
        }

        return START_STICKY
    }

    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)

                if (AutoCapturePauseStore.isPaused(applicationContext)) {
                    scheduleAfterPauseIfFinite("WATCHDOG_PAUSE")
                    continue
                }

                if (AppLaunchGuard.isActive()) {
                    Log.d(
                        TAG,
                        "와치독: StoryWing 앱 실행 보호 구간 - 캡처 Activity 실행 스킵. " +
                                "package=${AppLaunchGuard.packageName()} " +
                                "remainingMs=${AppLaunchGuard.remainingMs()}"
                    )
                    continue
                }

                KeepAliveController.acquire(applicationContext)

                val power = currentPowerState()
                if (!power.first || power.second) {
                    continue
                }

                if (shouldForceRefreshCapture()) {
                    scheduleRecovery(
                        reason = "WATCHDOG",
                        delayMs = 0L,
                        priority = PRIORITY_AUTO
                    )
                }
            }
        }
    }

    private fun shouldForceRefreshCapture(): Boolean {
        if (AppLaunchGuard.isActive()) {
            return false
        }

        val snapshot = CaptureStateStore.snapshot()

        return when (snapshot.state) {
            CaptureStateStore.STATE_WAITING_PERMISSION -> {
                snapshot.stateDurationMs >= WAITING_PERMISSION_TIMEOUT_MS
            }

            CaptureStateStore.STATE_STARTING -> {
                snapshot.stateDurationMs >= STARTING_TIMEOUT_MS
            }

            CaptureStateStore.STATE_RUNNING -> {
                when {
                    !CaptureService.isServiceAlive -> true
                    snapshot.stateDurationMs < RUNNING_GRACE_MS -> false
                    snapshot.frameAgeMs < 0L -> true
                    snapshot.frameAgeMs > STALE_RUNNING_FRAME_MS -> true
                    else -> false
                }
            }

            CaptureStateStore.STATE_IDLE,
            CaptureStateStore.STATE_STOPPED,
            CaptureStateStore.STATE_ERROR,
            CaptureStateStore.STATE_FROZEN,
            "" -> !CaptureService.isCleanupInProgress

            else -> !CaptureService.isServiceAlive
        }
    }

    private fun isCaptureHealthy(): Boolean {
        val snapshot = CaptureStateStore.snapshot()
        return CaptureService.isServiceAlive &&
                snapshot.state == CaptureStateStore.STATE_RUNNING &&
                snapshot.encodedFrameCount > 0L &&
                snapshot.frameAgeMs in 0..STALE_RUNNING_FRAME_MS
    }

    private fun scheduleRecovery(
        reason: String,
        delayMs: Long,
        operationId: String = "",
        restart: Boolean = false,
        priority: Int,
        addJitter: Boolean = true
    ) {
        val jitter = if (addJitter) recoveryJitterMs() else 0L
        val totalDelay = delayMs.coerceAtLeast(0L) + jitter
        val dueAt = SystemClock.elapsedRealtime() + totalDelay
        val token: Long

        synchronized(scheduleLock) {
            val currentActive = pendingRecoveryJob?.isActive == true
            val currentIsBetter =
                pendingRecoveryPriority > priority ||
                        (pendingRecoveryPriority == priority &&
                                pendingRecoveryDueAtElapsedMs <= dueAt)
            val keepCurrent = currentActive && currentIsBetter

            if (keepCurrent) {
                if (operationId.isNotBlank()) {
                    AgentOperationStore.fail(
                        operationId,
                        "higher_priority_recovery_already_scheduled",
                        retryable = true
                    )
                }
                return
            }

            val supersededOperationId = pendingRecoveryOperationId
            if (currentActive &&
                supersededOperationId.isNotBlank() &&
                supersededOperationId != operationId
            ) {
                AgentOperationStore.cancel(
                    supersededOperationId,
                    "capture_request_superseded"
                )
            }

            pendingRecoveryJob?.cancel()
            pendingRecoveryToken += 1L
            token = pendingRecoveryToken
            pendingRecoveryDueAtElapsedMs = dueAt
            pendingRecoveryPriority = priority
            pendingRecoveryOperationId = operationId

            pendingRecoveryJob = scope.launch {
                delay(totalDelay)

                synchronized(scheduleLock) {
                    if (token != pendingRecoveryToken) {
                        return@launch
                    }
                    pendingRecoveryJob = null
                    pendingRecoveryDueAtElapsedMs = Long.MAX_VALUE
                    pendingRecoveryPriority = Int.MIN_VALUE
                    pendingRecoveryOperationId = ""
                }

                recoveryMutex.withLock {
                    performCaptureRecovery(
                        reason = reason,
                        requestedOperationId = operationId,
                        restart = restart,
                        explicit = priority >= PRIORITY_EXPLICIT
                    )
                }
            }
        }

        Log.i(
            TAG,
            "Recovery scheduled. reason=$reason delay=${totalDelay}ms restart=$restart priority=$priority"
        )
    }

    private suspend fun performCaptureRecovery(
        reason: String,
        requestedOperationId: String,
        restart: Boolean,
        explicit: Boolean
    ) {
        KeepAliveController.acquire(applicationContext)

        if (AutoCapturePauseStore.isPaused(applicationContext) && !explicit) {
            scheduleAfterPauseIfFinite("PAUSED_$reason")
            return
        }

        if (
            deferRecoveryForStoryWingLaunch(
                reason = reason,
                requestedOperationId = requestedOperationId,
                restart = restart,
                explicit = explicit,
                phase = "entry"
            )
        ) {
            return
        }

        val power = currentPowerState()
        if (!power.first || power.second) {
            if (requestedOperationId.isNotBlank()) {
                AgentOperationStore.fail(
                    requestedOperationId,
                    if (!power.first) "device_not_interactive" else "device_idle",
                    retryable = true
                )
            }
            return
        }

        var operationId = requestedOperationId
        val snapshot = CaptureStateStore.snapshot()

        if (!restart && isCaptureHealthy()) {
            if (operationId.isNotBlank()) {
                AgentOperationStore.complete(
                    operationId,
                    "capture_already_running"
                )
            }
            return
        }

        if (!explicit) {
            val sinceLastRecovery =
                SystemClock.elapsedRealtime() - lastRecoveryAtElapsedMs
            if (sinceLastRecovery in 0 until RECOVERY_COOLDOWN_MS) {
                return
            }
        }

        if (
            snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION &&
            snapshot.stateDurationMs >= WAITING_PERMISSION_TIMEOUT_MS
        ) {
            AgentOperationStore.failPendingCapture(
                "waiting_permission_timeout",
                retryable = true
            )
        }

        if (
            snapshot.state == CaptureStateStore.STATE_STARTING &&
            snapshot.stateDurationMs >= STARTING_TIMEOUT_MS
        ) {
            AgentOperationStore.failPendingCapture(
                "capture_starting_timeout",
                retryable = true
            )
        }

        lastRecoveryAtElapsedMs = SystemClock.elapsedRealtime()

        if (operationId.isBlank()) {
            operationId = AgentOperationStore.begin(
                type = if (restart) {
                    AgentOperationStore.TYPE_CAPTURE_RESTART
                } else {
                    AgentOperationStore.TYPE_CAPTURE_START
                },
                reason = reason
            )
        }

        /*
         * recovery 작업이 대기하는 동안 StoryWing launch가 새로 시작됐을 수 있습니다.
         * CaptureService 정리나 권한 Activity 실행 직전에 다시 확인합니다.
         */
        if (
            deferRecoveryForStoryWingLaunch(
                reason = reason,
                requestedOperationId = operationId,
                restart = restart,
                explicit = explicit,
                phase = "before_capture_cleanup"
            )
        ) {
            return
        }

        if (
            CaptureService.isServiceAlive ||
            CaptureService.isCleanupInProgress ||
            snapshot.state == CaptureStateStore.STATE_RUNNING ||
            snapshot.state == CaptureStateStore.STATE_STARTING ||
            snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION ||
            restart
        ) {
            CaptureStateStore.markFrozen("recovery:$reason")
            CaptureService.stop(applicationContext)

            val stopped = CaptureService.awaitFullyStopped(8_000L)
            if (!stopped) {
                val message = "capture_cleanup_timeout"
                CaptureStateStore.setError(message)
                AgentOperationStore.fail(
                    operationId,
                    message,
                    retryable = true
                )
                updateNotification("송출 정리 시간 초과", highPriority = true)
                return
            }
        }

        val powerAfterStop = currentPowerState()
        if (!powerAfterStop.first || powerAfterStop.second) {
            val message = if (!powerAfterStop.first) {
                "device_not_interactive_after_cleanup"
            } else {
                "device_idle_after_cleanup"
            }
            CaptureStateStore.setError(message)
            AgentOperationStore.fail(operationId, message, retryable = true)
            return
        }

        if (
            deferRecoveryForStoryWingLaunch(
                reason = reason,
                requestedOperationId = operationId,
                restart = restart,
                explicit = explicit,
                phase = "before_permission_activity"
            )
        ) {
            return
        }

        CaptureStateStore.clearToIdle(clearOperation = false)
        CaptureStateStore.activeOperationId = operationId
        AgentOperationStore.markDispatched(
            operationId,
            "opening CapturePermissionActivity"
        )

        val opened = openCapturePermissionActivity(
            reason = reason,
            operationId = operationId
        )

        if (!opened) {
            val message = "capture_permission_activity_start_failed"
            CaptureStateStore.setError(message)
            AgentOperationStore.fail(operationId, message, retryable = true)
            updateNotification("화면공유 권한 화면 실행 실패", highPriority = true)
            return
        }

        verifyCaptureActivityLaunch(operationId)
    }

    /**
     * StoryWing/OpenXR 앱 첫 프레임 보호 중에는 자동/수동/명시적 요청을 가리지 않고
     * CapturePermissionActivity 실행을 연기합니다.
     *
     * explicit 요청까지 통과시키던 기존 코드는 투명 MediaProjection Activity가
     * XR 앱 위로 올라와 오디오는 재생되지만 화면은 Meta 로딩 공간에 남는
     * 포커스 경쟁을 만들 수 있었습니다.
     */
    private fun deferRecoveryForStoryWingLaunch(
        reason: String,
        requestedOperationId: String,
        restart: Boolean,
        explicit: Boolean,
        phase: String
    ): Boolean {
        if (!AppLaunchGuard.isActive()) {
            return false
        }

        val delayMs =
            AppLaunchGuard.remainingMs() +
                    APP_LAUNCH_GUARD_RECHECK_PADDING_MS

        /*
         * guard 만료 뒤 재예약된 복구가 RECOVERY_COOLDOWN_MS에 막히지 않게 합니다.
         */
        lastRecoveryAtElapsedMs = 0L

        Log.i(
            TAG,
            "Capture recovery deferred by StoryWing launch guard. " +
                    "phase=$phase reason=$reason " +
                    "package=${AppLaunchGuard.packageName()} " +
                    "delayMs=$delayMs explicit=$explicit restart=$restart " +
                    "operationId=$requestedOperationId"
        )

        scheduleRecovery(
            reason = "APP_LAUNCH_GUARD_$reason",
            delayMs = delayMs,
            operationId = requestedOperationId,
            restart = restart,
            priority = if (explicit) {
                PRIORITY_EXPLICIT
            } else {
                PRIORITY_AUTO
            },
            addJitter = false
        )

        return true
    }

    private fun verifyCaptureActivityLaunch(operationId: String) {
        scope.launch {
            delay(ACTIVITY_LAUNCH_VERIFY_MS)

            val state = AgentOperationStore.stateOf(operationId)
            val capture = CaptureStateStore.snapshot()

            val activityFlowStarted =
                state == AgentOperationStore.STATE_WAITING_PERMISSION ||
                        state == AgentOperationStore.STATE_STARTING ||
                        state == AgentOperationStore.STATE_COMPLETED ||
                        capture.state == CaptureStateStore.STATE_WAITING_PERMISSION ||
                        capture.state == CaptureStateStore.STATE_STARTING ||
                        capture.state == CaptureStateStore.STATE_RUNNING

            if (!activityFlowStarted && AgentOperationStore.isPending(operationId)) {
                val message = "background_activity_start_blocked"
                CaptureStateStore.setError(message)
                AgentOperationStore.fail(
                    operationId,
                    message,
                    retryable = true
                )
                updateNotification(
                    "권한 화면이 차단되었습니다. Agent를 한 번 열어 주십시오.",
                    highPriority = true
                )
            }
        }
    }

    private fun openCapturePermissionActivity(
        reason: String,
        operationId: String
    ): Boolean {
        if (AppLaunchGuard.isActive()) {
            Log.w(
                TAG,
                "CapturePermissionActivity launch blocked by late StoryWing guard. " +
                        "package=${AppLaunchGuard.packageName()} " +
                        "reason=$reason operationId=$operationId"
            )
            return false
        }

        val intent = CapturePermissionActivity.createIntent(
            context = this,
            reason = reason,
            operationId = operationId
        )

        return try {
            Log.w(
                TAG,
                "CapturePermissionActivity open. reason=$reason operationId=$operationId"
            )
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "CapturePermissionActivity open failed", e)
            false
        }
    }

    private fun scheduleAfterPauseIfFinite(reason: String) {
        val remaining = AutoCapturePauseStore.remainingMs(applicationContext)
        if (remaining <= 0L || remaining == Long.MAX_VALUE) return

        scheduleRecovery(
            reason = reason,
            delayMs = remaining + 500L,
            priority = PRIORITY_AUTO,
            addJitter = false
        )
    }

    private fun cancelPendingRecovery() {
        synchronized(scheduleLock) {
            pendingRecoveryToken += 1L
            pendingRecoveryJob?.cancel()
            pendingRecoveryJob = null
            pendingRecoveryDueAtElapsedMs = Long.MAX_VALUE
            pendingRecoveryPriority = Int.MIN_VALUE
            pendingRecoveryOperationId = ""
        }
    }

    private fun currentPowerState(): Pair<Boolean, Boolean> {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val idle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    pm.isDeviceIdleMode
            pm.isInteractive to idle
        } catch (_: Exception) {
            false to false
        }
    }

    private fun recoveryJitterMs(): Long {
        return try {
            val id = StableDeviceId.get(applicationContext)
            val positiveHash = id.hashCode().toLong() and 0x7FFF_FFFFL
            positiveHash % 2_500L
        } catch (_: Exception) {
            0L
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text, highPriority = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        text: String,
        highPriority: Boolean
    ) {
        try {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(text, highPriority)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed", e)
        }
    }

    private fun buildNotification(
        text: String,
        highPriority: Boolean
    ): android.app.Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

        val pendingIntent = PendingIntent.getActivity(
            this,
            24001,
            mainIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(
            this,
            if (highPriority) RECOVERY_CHANNEL_ID else CHANNEL_ID
        )
            .setContentTitle("MultiQuest")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(
                if (highPriority) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_LOW
                }
            )
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        manager.createNotificationChannel(
            NotificationChannel(
                RECOVERY_CHANNEL_ID,
                "Recovery",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun acquireMulticastLock() {
        try {
            val wifi =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            multicastLock = wifi.createMulticastLock(
                "MultiQuest:MulticastLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            Log.i(TAG, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Multicast lock acquire failed", e)
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                KeepAliveController.acquire(applicationContext)

                mainHandler.post {
                    scheduleNsdReregister()
                }

                if (!isCaptureHealthy()) {
                    scheduleRecovery(
                        reason = "NETWORK_AVAILABLE",
                        delayMs = NETWORK_AVAILABLE_CHECK_DELAY_MS,
                        priority = PRIORITY_EVENT
                    )
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Wi-Fi network lost")
            }

            override fun onUnavailable() {
                Log.w(TAG, "Wi-Fi network unavailable")
            }
        }

        networkCallback = callback

        try {
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Network callback register failed", e)
        }
    }

    /**
     * Wi-Fi 재연결 또는 IP 변경 뒤 NSD 광고를 안전하게 다시 등록합니다.
     *
     * AgentNsdAdvertiser에 reRegister()가 없어도 동작하도록
     * 기존 광고를 해제한 뒤 잠시 기다렸다가 register()를 다시 호출합니다.
     */
    private fun scheduleNsdReregister() {
        cancelPendingNsdReregister()

        if (!::nsdAdvertiser.isInitialized || statusPort <= 0) {
            return
        }

        try {
            nsdAdvertiser.unregister()
        } catch (e: Exception) {
            Log.w(TAG, "NSD unregister before re-register failed", e)
        }

        val runnable = Runnable {
            pendingNsdReregisterRunnable = null

            if (!::nsdAdvertiser.isInitialized || statusPort <= 0) {
                return@Runnable
            }

            try {
                nsdAdvertiser.register(
                    statusPort = statusPort,
                    rtspPort = RTSP_PORT
                )

                Log.i(
                    TAG,
                    "NSD re-registered. statusPort=$statusPort rtspPort=$RTSP_PORT"
                )
            } catch (e: Exception) {
                Log.e(TAG, "NSD re-register failed", e)
            }
        }

        pendingNsdReregisterRunnable = runnable
        mainHandler.postDelayed(runnable, 750L)
    }

    private fun cancelPendingNsdReregister() {
        pendingNsdReregisterRunnable?.let { runnable ->
            try {
                mainHandler.removeCallbacks(runnable)
            } catch (_: Exception) {
            }
        }

        pendingNsdReregisterRunnable = null
    }

    private fun registerPowerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                powerReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(powerReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelPendingRecovery()
        cancelPendingNsdReregister()

        try {
            if (::httpServer.isInitialized) httpServer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server stop failed", e)
        }

        try {
            if (::nsdAdvertiser.isInitialized) nsdAdvertiser.unregister()
        } catch (e: Exception) {
            Log.e(TAG, "NSD unregister failed", e)
        }

        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network callback unregister failed", e)
        }
        networkCallback = null

        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MulticastLock release failed", e)
        }
        multicastLock = null

        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {
        }

        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }

        KeepAliveController.release()
        scope.cancel()
        super.onDestroy()
    }
}