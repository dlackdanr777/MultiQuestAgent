package tdg.oculuswirelessadb.agent

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tdg.oculuswirelessadb.CapturePermissionActivity
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore

class AgentService : Service() {

    companion object {
        const val ACTION_START = "tdg.oculuswirelessadb.agent.START"
        const val ACTION_STOP = "tdg.oculuswirelessadb.agent.STOP"

        private const val TAG = "MQ-Agent"

        private const val CHANNEL_ID = "multiquest_agent"
        private const val RECOVERY_CHANNEL_ID = "multiquest_recovery"

        private const val NOTIFICATION_ID = 24001

        const val RTSP_PORT = 8554

        private const val WATCHDOG_INTERVAL_MS = 10_000L

        // 기존 10초는 너무 짧아서 WPF/VLC 재연결 중 반복 재시작을 만들 수 있습니다.
        private const val RECOVERY_COOLDOWN_MS = 30_000L

        // 기존 4초는 MediaProjection/Codec/RTSP 준비 시간으로 짧습니다.
        private const val STARTING_TIMEOUT_MS = 15_000L

        private const val SERVICE_START_RECOVERY_CHECK_DELAY_MS = 12_000L
        private const val SCREEN_ON_FORCE_REFRESH_DELAY_MS = 7_000L
        private const val WAKE_FROM_IDLE_FORCE_REFRESH_DELAY_MS = 8_000L
        private const val NETWORK_AVAILABLE_FORCE_REFRESH_DELAY_MS = 5_000L
        private const val ON_START_COMMAND_REFRESH_DELAY_MS = 4_000L

        private const val STOP_TO_START_DELAY_MS = 2_500L

        // 기존 8초는 너무 짧습니다.
        // WPF/VLC가 붙는 중이거나 키프레임 대기 중이면 쉽게 false positive가 납니다.
        private const val STALE_RUNNING_FRAME_MS = 60_000L

        // RUNNING 진입 직후에는 절대 강제 재시작하지 않습니다.
        private const val RUNNING_GRACE_MS = 30_000L

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
            context.startService(intent)
        }
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var httpServer: SimpleStatusHttpServer
    private lateinit var nsdAdvertiser: AgentNsdAdvertiser

    private var statusPort: Int = 0
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastRecoveryTime = 0L

    private var pendingRefreshRunnable: Runnable? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "화면 꺼짐 감지 - 화면공유 세션 정리 및 앱 복귀 가능 상태 확정")

                    KeepAliveController.acquire(applicationContext)

                    stopCaptureForSleep("SCREEN_OFF")
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "화면 켜짐 감지 - 화면공유 세션 강제 새로고침 예약")

                    KeepAliveController.acquire(applicationContext)

                    scheduleForceRefreshCapture(
                        reason = "SCREEN_ON_FORCE_REFRESH",
                        delayMs = SCREEN_ON_FORCE_REFRESH_DELAY_MS
                    )
                }

                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

                        if (pm.isDeviceIdleMode) {
                            Log.i(TAG, "딥슬립 진입 감지 - 화면공유 세션 정리 및 앱 복귀 가능 상태 확정")

                            KeepAliveController.acquire(applicationContext)

                            stopCaptureForSleep("DEVICE_IDLE")
                        } else {
                            Log.i(TAG, "딥슬립 해제 감지 - 화면공유 세션 강제 새로고침 예약")

                            KeepAliveController.acquire(applicationContext)

                            scheduleForceRefreshCapture(
                                reason = "WAKE_FROM_DEVICE_IDLE_FORCE_REFRESH",
                                delayMs = WAKE_FROM_IDLE_FORCE_REFRESH_DELAY_MS
                            )
                        }
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

        httpServer = SimpleStatusHttpServer(applicationContext, RTSP_PORT, scope)
        statusPort = httpServer.start()

        nsdAdvertiser = AgentNsdAdvertiser(applicationContext)
        nsdAdvertiser.register(statusPort, RTSP_PORT)

        registerNetworkCallback()
        registerPowerReceiver()

        startWatchdog()

        scheduleForceRefreshCapture(
            reason = "SERVICE_START_FORCE_REFRESH",
            delayMs = SERVICE_START_RECOVERY_CHECK_DELAY_MS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        KeepAliveController.acquire(applicationContext)

        // 중요:
        // AgentService.start()가 다시 들어왔다는 이유만으로
        // 현재 RUNNING 중인 화면공유를 끊으면 안 됩니다.
        if (isCaptureRunningOrStarting()) {
            Log.i(TAG, "onStartCommand: capture already running/starting, refresh skipped")
            return START_STICKY
        }

        scheduleForceRefreshCapture(
            reason = "ON_START_COMMAND_FORCE_REFRESH",
            delayMs = ON_START_COMMAND_REFRESH_DELAY_MS
        )

        return START_STICKY
    }

    private fun stopCaptureForSleep(reason: String) {
        cancelPendingRefresh()

        try {
            // 여기서 clear() 하면 열려 있던 앱 복귀까지 사라집니다.
            // prepareForSleep()은 현재 appOpen/restoreAllowed 상태만 확정합니다.
            LastLaunchedAppStore.prepareForSleep(
                context = applicationContext,
                reason = reason
            )
        } catch (e: Exception) {
            Log.e(TAG, "prepareForSleep failed. reason=$reason", e)
        }

        try {
            CaptureStateStore.markStopped()
        } catch (_: Exception) {
        }

        try {
            CaptureService.stop(applicationContext)
            Log.i(TAG, "CaptureService stop requested for sleep. reason=$reason")
        } catch (e: Exception) {
            Log.e(TAG, "CaptureService stop failed for sleep. reason=$reason", e)
        }
    }

    private fun registerPowerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }

        registerReceiver(powerReceiver, filter)
    }

    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)

                if (AutoCapturePauseStore.isPaused(applicationContext)) {
                    continue
                }

                KeepAliveController.acquire(applicationContext)

                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val interactive = pm.isInteractive
                val idle =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            pm.isDeviceIdleMode

                if (!interactive) {
                    Log.d(TAG, "와치독: 화면 꺼짐 상태 - 화면공유 복구 스킵")
                    continue
                }

                if (idle) {
                    Log.d(TAG, "와치독: 딥슬립 상태 - 화면공유 복구 스킵")
                    continue
                }

                if (shouldForceRefreshCapture()) {
                    val jitter = recoveryJitterMs()

                    Log.w(TAG, "와치독: 화면공유 새로고침 필요 감지. jitter=${jitter}ms")

                    delay(jitter)

                    scheduleForceRefreshCapture(
                        reason = "WATCHDOG_FORCE_REFRESH",
                        delayMs = 0L
                    )
                }
            }
        }
    }

    private fun shouldForceRefreshCapture(): Boolean {
        val serviceRunning = CaptureService.isRunning
        val state = CaptureStateStore.state
        val stateDuration = CaptureStateStore.getStateDurationMs()
        val frameAgeMs = CaptureStateStore.getFrameAgeMs()
        val clientCount = CaptureStateStore.rtspClientCount

        if (state == CaptureStateStore.STATE_WAITING_PERMISSION) {
            return false
        }

        if (state == CaptureStateStore.STATE_STARTING) {
            return stateDuration >= STARTING_TIMEOUT_MS
        }

        if (state == CaptureStateStore.STATE_RUNNING) {
            if (!serviceRunning) {
                Log.w(TAG, "RUNNING 상태지만 CaptureService.isRunning=false")
                CaptureStateStore.markFrozen()
                return true
            }

            // RUNNING 진입 직후에는 WPF/VLC가 붙고 키프레임을 기다리는 시간이 필요합니다.
            // 이 구간에서 재시작하면 1프레임 루프가 생깁니다.
            if (stateDuration < RUNNING_GRACE_MS) {
                Log.d(
                    TAG,
                    "RUNNING grace 구간 - 복구 스킵. stateDuration=$stateDuration"
                )
                return false
            }

            // RTSP 클라이언트가 없는 상태에서는 프레임 지연으로 판단하지 않습니다.
            // WPF가 재연결 중일 수 있기 때문입니다.
            if (clientCount <= 0) {
                Log.d(TAG, "RUNNING but no RTSP client - 복구 스킵")
                return false
            }

            // 첫 프레임 전이면 아직 인코더/RTSP 안정화 구간일 수 있습니다.
            if (frameAgeMs < 0L) {
                Log.d(TAG, "RUNNING but no frame yet - 복구 스킵")
                return false
            }

            // 진짜로 60초 이상 프레임이 멈춘 경우에만 복구합니다.
            if (frameAgeMs > STALE_RUNNING_FRAME_MS) {
                Log.w(
                    TAG,
                    "RUNNING 상태지만 장시간 프레임 지연. " +
                            "frameAgeMs=$frameAgeMs, clientCount=$clientCount"
                )
                CaptureStateStore.markFrozen()
                return true
            }

            return false
        }

        return !serviceRunning ||
                state == CaptureStateStore.STATE_IDLE ||
                state == CaptureStateStore.STATE_STOPPED ||
                state == CaptureStateStore.STATE_ERROR ||
                state == CaptureStateStore.STATE_FROZEN ||
                state.isBlank()
    }

    private fun scheduleForceRefreshCapture(reason: String, delayMs: Long) {
        cancelPendingRefresh()

        val totalDelay = delayMs + recoveryJitterMs()

        val runnable = Runnable {
            forceRefreshCaptureSession(reason)
        }

        pendingRefreshRunnable = runnable

        mainHandler.postDelayed(runnable, totalDelay)
    }

    private fun cancelPendingRefresh() {
        pendingRefreshRunnable?.let {
            try {
                mainHandler.removeCallbacks(it)
            } catch (_: Exception) {
            }
        }

        pendingRefreshRunnable = null
    }

    private fun forceRefreshCaptureSession(reason: String) {
        try {
            KeepAliveController.acquire(applicationContext)

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (!pm.isInteractive) {
                Log.i(TAG, "화면공유 새로고침 스킵: 화면 꺼짐. reason=$reason")
                return
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                pm.isDeviceIdleMode
            ) {
                Log.i(TAG, "화면공유 새로고침 스킵: 딥슬립 상태. reason=$reason")
                return
            }

            if (AutoCapturePauseStore.isPaused(applicationContext)) {
                Log.i(TAG, "화면공유 새로고침 스킵: AutoCapture paused. reason=$reason")
                return
            }

            // 중요:
            // 외부 이벤트 onStartCommand / Network / ScreenOn 때문에 예약된 refresh라도
            // 실제 실행 시점에 이미 정상 RUNNING이면 절대 끊지 않습니다.
            if (shouldSkipRefreshBecauseCaptureIsActive(reason)) {
                return
            }

            val now = System.currentTimeMillis()

            if (now - lastRecoveryTime < RECOVERY_COOLDOWN_MS) {
                Log.i(TAG, "화면공유 새로고침 스킵: cooldown. reason=$reason")
                return
            }

            lastRecoveryTime = now

            Log.w(TAG, "화면공유 세션 강제 새로고침 시작. reason=$reason")

            try {
                CaptureStateStore.markFrozen()
            } catch (_: Exception) {
            }

            try {
                CaptureService.stop(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "CaptureService stop before refresh failed", e)
            }

            mainHandler.postDelayed({
                try {
                    val pm2 = getSystemService(Context.POWER_SERVICE) as PowerManager

                    if (!pm2.isInteractive) {
                        Log.i(TAG, "권한 요청 스킵: stop 후 화면 꺼짐. reason=$reason")
                        CaptureStateStore.markStopped()
                        return@postDelayed
                    }

                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        pm2.isDeviceIdleMode
                    ) {
                        Log.i(TAG, "권한 요청 스킵: stop 후 딥슬립. reason=$reason")
                        CaptureStateStore.markStopped()
                        return@postDelayed
                    }

                    CaptureStateStore.clearToIdle()

                    openCapturePermissionActivity("FORCE_REFRESH_$reason")
                } catch (e: Exception) {
                    Log.e(TAG, "Delayed capture refresh failed. reason=$reason", e)
                    CaptureStateStore.setError(e.message ?: e.javaClass.simpleName)
                }
            }, STOP_TO_START_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "forceRefreshCaptureSession failed. reason=$reason", e)
        }
    }

    private fun shouldSkipRefreshBecauseCaptureIsActive(reason: String): Boolean {
        val state = CaptureStateStore.state
        val stateDuration = CaptureStateStore.getStateDurationMs()
        val frameAgeMs = CaptureStateStore.getFrameAgeMs()
        val clientCount = CaptureStateStore.rtspClientCount
        val serviceRunning = CaptureService.isRunning

        if (state == CaptureStateStore.STATE_WAITING_PERMISSION ||
            state == CaptureStateStore.STATE_STARTING
        ) {
            Log.i(
                TAG,
                "화면공유 새로고침 스킵: 권한/시작 처리 중. " +
                        "reason=$reason state=$state"
            )
            return true
        }

        if (state == CaptureStateStore.STATE_RUNNING && serviceRunning) {
            if (stateDuration < RUNNING_GRACE_MS) {
                Log.i(
                    TAG,
                    "화면공유 새로고침 스킵: RUNNING grace 구간. " +
                            "reason=$reason stateDuration=$stateDuration"
                )
                return true
            }

            if (clientCount <= 0) {
                Log.i(
                    TAG,
                    "화면공유 새로고침 스킵: RUNNING but clientCount=0. " +
                            "reason=$reason"
                )
                return true
            }

            if (frameAgeMs < 0L) {
                Log.i(
                    TAG,
                    "화면공유 새로고침 스킵: RUNNING but no frame yet. " +
                            "reason=$reason"
                )
                return true
            }

            if (frameAgeMs <= STALE_RUNNING_FRAME_MS) {
                Log.i(
                    TAG,
                    "화면공유 새로고침 스킵: 이미 정상 RUNNING. " +
                            "reason=$reason frameAgeMs=$frameAgeMs clientCount=$clientCount"
                )
                return true
            }

            // 여기까지 오면 RUNNING인데 장시간 프레임 정지 상태입니다.
            // 이 경우에는 강제 복구를 허용합니다.
            Log.w(
                TAG,
                "RUNNING but stale enough to refresh. " +
                        "reason=$reason frameAgeMs=$frameAgeMs clientCount=$clientCount"
            )
            return false
        }

        return false
    }

    private fun isCaptureRunningOrStarting(): Boolean {
        val state = CaptureStateStore.state

        return CaptureService.isRunning ||
                state == CaptureStateStore.STATE_RUNNING ||
                state == CaptureStateStore.STATE_STARTING ||
                state == CaptureStateStore.STATE_WAITING_PERMISSION
    }

    private fun openCapturePermissionActivity(reason: String): Boolean {
        val intent = CapturePermissionActivity.createIntent(
            context = this,
            reason = reason
        ).apply {
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
            Log.w(TAG, "CapturePermissionActivity open. reason=$reason")
            startActivity(intent, options)
            true
        } catch (e: Exception) {
            Log.e(TAG, "CapturePermissionActivity open failed. reason=$reason", e)
            false
        }
    }

    private fun recoveryJitterMs(): Long {
        return try {
            val id = Build.SERIAL ?: "default"
            (kotlin.math.abs(id.hashCode()) % 2500).toLong()
        } catch (_: Exception) {
            (0..2500).random().toLong()
        }
    }

    private fun startAsForeground(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiQuest")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Agent Service",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    "Recovery",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            multicastLock = wifi.createMulticastLock("MultiQuest:MulticastLock").apply {
                setReferenceCounted(true)
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
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                KeepAliveController.acquire(applicationContext)

                if (isCaptureRunningOrStarting()) {
                    Log.i(TAG, "Network available - capture already running/starting, refresh skipped")
                    return
                }

                Log.i(TAG, "Network available - 화면공유 세션 새로고침 예약")

                scheduleForceRefreshCapture(
                    reason = "NETWORK_AVAILABLE_FORCE_REFRESH",
                    delayMs = NETWORK_AVAILABLE_FORCE_REFRESH_DELAY_MS
                )
            }

            override fun onLost(network: android.net.Network) {
                Log.w(TAG, "Network lost")
            }

            override fun onUnavailable() {
                Log.w(TAG, "Network unavailable")
            }
        }

        networkCallback = callback

        try {
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Network callback register failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelPendingRefresh()

        try {
            if (::httpServer.isInitialized) {
                httpServer.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server stop failed", e)
        }

        try {
            if (::nsdAdvertiser.isInitialized) {
                nsdAdvertiser.unregister()
            }
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
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MulticastLock release failed", e)
        }

        multicastLock = null

        try {
            KeepAliveController.release()
        } catch (_: Exception) {
        }

        try {
            unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Power receiver unregister failed", e)
        }

        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }

        serviceJob.cancel()

        super.onDestroy()
    }
}