package tdg.oculuswirelessadb

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import tdg.oculuswirelessadb.agent.AgentNameStore
import tdg.oculuswirelessadb.agent.AgentOperationStore
import tdg.oculuswirelessadb.agent.AgentService
import tdg.oculuswirelessadb.agent.AgentUiState
import tdg.oculuswirelessadb.agent.AutoCapturePauseStore
import tdg.oculuswirelessadb.agent.KeepAliveController
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val REQUEST_CAPTURE_CODE = 1000
        private const val REQUEST_NOTIFICATION_CODE = 1001

        private const val AUTO_CAPTURE_DELAY_MS = 350L
        private const val MIN_CAPTURE_REQUEST_INTERVAL_MS = 1_500L
        private const val PERMISSION_RESULT_TIMEOUT_MS = 30_000L
        private const val WAIT_RUNNING_TIMEOUT_MS = 25_000L
        private const val WAIT_RUNNING_INTERVAL_MS = 250L
        private const val STALE_WAITING_PERMISSION_MS = 35_000L
        private const val STALE_STARTING_MS = 30_000L
        private const val SUPPRESS_AUTO_START_MS = 5_000L
        private const val DENIED_RETRY_PAUSE_MS = 2 * 60 * 1000L

        private const val CLOSE_BUTTON_SIZE_PX = 48
        private const val CLOSE_BUTTON_MARGIN_PX = 14

        private var currentRef: WeakReference<MainActivity>? = null
        private val companionHandler = Handler(Looper.getMainLooper())

        /**
         * WPF 종료 명령에서 Agent 설정 UI를 즉시 닫기 위한 함수입니다.
         */
        fun finishCurrent() {
            val activity = currentRef?.get()

            companionHandler.post {
                try {
                    if (activity != null &&
                        !activity.isFinishing &&
                        !activity.isDestroyed
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            activity.finishAndRemoveTask()
                        } else {
                            activity.finish()
                        }
                        activity.overridePendingTransition(0, 0)
                    }
                } catch (_: Exception) {
                } finally {
                    if (currentRef?.get() === activity) {
                        currentRef = null
                    }
                }
            }
        }
    }

    private lateinit var statusTextView: TextView
    private lateinit var nameInput: EditText
    private lateinit var projectionManager: MediaProjectionManager

    private val handler = Handler(Looper.getMainLooper())

    private var isWaitingForPermission = false
    private var isPermissionLaunchScheduled = false
    private var isUiHideInProgress = false
    private var notificationPermissionRequestInFlight = false

    private var lastRequestAtElapsedMs = 0L
    private var suppressAutoStartUntilElapsedMs = 0L
    private var activeOperationId: String = ""

    private val permissionTimeoutRunnable = Runnable {
        if (!isWaitingForPermission || isUiHideInProgress) {
            return@Runnable
        }

        val message = "media_projection_permission_timeout"
        Log.w(TAG, "$message operationId=$activeOperationId")

        isWaitingForPermission = false
        CaptureStateStore.setError(message)
        AgentOperationStore.fail(
            activeOperationId,
            message,
            retryable = true
        )
        updateStatusUI()
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                updateStatusUI()
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentRef = WeakReference(this)

        // 종료 명령에서 표시했던 검은 차단 화면을 Agent 직접 오픈 시 해제합니다.
        IdleShieldActivity.finishCurrent()

        // Agent 앱을 직접 열면 수동/무기한 중지를 해제하고 자동 송출을 시작합니다.
        AutoCapturePauseStore.resume(this)

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0.0f)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        AgentService.start(this)
        KeepAliveController.acquire(this)

        setContentView(createContentView())
        handler.post(statusUpdater)

        consumeSuppressAutoStartFlag()

        if (shouldAutoCaptureRequest() && isScreenInteractive()) {
            setupShowOnLockScreen()
        }

        notificationPermissionRequestInFlight =
            requestPostNotificationsIfNeeded()

        if (!notificationPermissionRequestInFlight) {
            scheduleAutoStartCheck("onCreate")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        IdleShieldActivity.finishCurrent()
        AutoCapturePauseStore.resume(this)

        consumeSuppressAutoStartFlag()

        if (shouldAutoCaptureRequest() && isScreenInteractive()) {
            setupShowOnLockScreen()
        }

        scheduleAutoStartCheck("onNewIntent", 0L)
    }

    override fun onResume() {
        super.onResume()

        isUiHideInProgress = false
        AgentUiState.isMainActivityVisible = true

        AgentService.start(this)
        KeepAliveController.acquire(this)
        IdleShieldActivity.finishCurrent()
        AutoCapturePauseStore.resume(this)

        consumeSuppressAutoStartFlag()
        scheduleAutoStartCheck("onResume", AUTO_CAPTURE_DELAY_MS)
    }

    private fun scheduleAutoStartCheck(
        source: String,
        delayMs: Long = AUTO_CAPTURE_DELAY_MS
    ) {
        handler.postDelayed(
            { checkAndAutoStart(source) },
            delayMs.coerceAtLeast(0L)
        )
    }

    private fun consumeSuppressAutoStartFlag() {
        val suppress = intent?.getBooleanExtra(
            "suppressAutoStartOnce",
            false
        ) == true

        if (suppress) {
            suppressAutoStartUntilElapsedMs =
                SystemClock.elapsedRealtime() + SUPPRESS_AUTO_START_MS
            intent?.removeExtra("suppressAutoStartOnce")
            Log.i(TAG, "자동 화면공유 시작 임시 억제 적용")
        }
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive && !(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            pm.isDeviceIdleMode
                    )
        } catch (_: Exception) {
            false
        }
    }

    private fun shouldAutoCaptureRequest(): Boolean {
        return intent?.getBooleanExtra("requestCapture", false) == true
    }

    private fun setupShowOnLockScreen() {
        if (!isScreenInteractive()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                setShowWhenLocked(true)
                setTurnScreenOn(true)

                val keyguardManager =
                    getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            } catch (e: Exception) {
                Log.w(TAG, "setupShowOnLockScreen failed", e)
            }
        }
    }

    private fun checkAndAutoStart(source: String) {
        if (isUiHideInProgress || notificationPermissionRequestInFlight) return
        if (!isScreenInteractive()) return

        val now = SystemClock.elapsedRealtime()
        if (now < suppressAutoStartUntilElapsedMs) return
        if (isWaitingForPermission || isPermissionLaunchScheduled) return

        val isAutoRequest = shouldAutoCaptureRequest()
        if (AutoCapturePauseStore.isPaused(this)) {
            if (isAutoRequest) {
                AutoCapturePauseStore.resume(this)
            } else {
                val remaining = AutoCapturePauseStore.remainingMs(this)
                if (remaining in 1 until Long.MAX_VALUE) {
                    scheduleAutoStartCheck(
                        source = "auto_pause_expired",
                        delayMs = remaining + 500L
                    )
                }
                return
            }
        }

        val snapshot = CaptureStateStore.snapshot()

        if (
            snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION &&
            snapshot.stateDurationMs >= STALE_WAITING_PERMISSION_MS
        ) {
            AgentOperationStore.failPendingCapture(
                "stale_waiting_permission",
                retryable = true
            )
            CaptureStateStore.setError("stale_waiting_permission")
            CaptureService.stop(this)
        } else if (
            snapshot.state == CaptureStateStore.STATE_STARTING &&
            snapshot.stateDurationMs >= STALE_STARTING_MS
        ) {
            AgentOperationStore.failPendingCapture(
                "stale_capture_starting",
                retryable = true
            )
            CaptureStateStore.setError("stale_capture_starting")
            CaptureService.stop(this)
        } else if (
            snapshot.state == CaptureStateStore.STATE_RUNNING ||
            snapshot.state == CaptureStateStore.STATE_STARTING ||
            snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION
        ) {
            return
        }

        val shouldStartFromNormalOpen =
            snapshot.state == CaptureStateStore.STATE_STOPPED ||
                    snapshot.state == CaptureStateStore.STATE_IDLE ||
                    snapshot.state == CaptureStateStore.STATE_ERROR ||
                    snapshot.state == CaptureStateStore.STATE_FROZEN ||
                    snapshot.state.isBlank()

        if (!isAutoRequest && !shouldStartFromNormalOpen) return
        if (now - lastRequestAtElapsedMs < MIN_CAPTURE_REQUEST_INTERVAL_MS) return

        val scheduled = scheduleCapturePermissionRequest(
            delayMs = AUTO_CAPTURE_DELAY_MS,
            reason = "auto_$source"
        )

        if (scheduled && isAutoRequest) {
            intent?.removeExtra("requestCapture")
        }
    }

    private fun scheduleCapturePermissionRequest(
        delayMs: Long,
        reason: String
    ): Boolean {
        if (isUiHideInProgress || !isScreenInteractive()) return false
        if (isWaitingForPermission || isPermissionLaunchScheduled) return false

        val snapshot = CaptureStateStore.snapshot()
        if (
            snapshot.state == CaptureStateStore.STATE_RUNNING ||
            (snapshot.state == CaptureStateStore.STATE_STARTING &&
                    snapshot.stateDurationMs < STALE_STARTING_MS) ||
            (snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION &&
                    snapshot.stateDurationMs < STALE_WAITING_PERMISSION_MS)
        ) {
            return false
        }

        if (activeOperationId.isBlank() ||
            !AgentOperationStore.isPending(activeOperationId)
        ) {
            activeOperationId = AgentOperationStore.begin(
                type = AgentOperationStore.TYPE_CAPTURE_START,
                reason = reason
            )
        }

        isPermissionLaunchScheduled = true
        lastRequestAtElapsedMs = SystemClock.elapsedRealtime()

        handler.postDelayed({
            isPermissionLaunchScheduled = false
            if (!isUiHideInProgress) {
                requestScreenCapturePermissionInternal(reason)
            }
        }, delayMs.coerceAtLeast(0L))

        return true
    }

    private fun requestScreenCapturePermission() {
        AutoCapturePauseStore.resume(this)
        activeOperationId = ""

        scheduleCapturePermissionRequest(
            delayMs = 0L,
            reason = "manual_button"
        )
    }

    private fun requestScreenCapturePermissionInternal(reason: String) {
        if (isUiHideInProgress || !isScreenInteractive()) return
        if (isWaitingForPermission) return

        val snapshot = CaptureStateStore.snapshot()
        if (
            snapshot.state == CaptureStateStore.STATE_RUNNING ||
            (snapshot.state == CaptureStateStore.STATE_STARTING &&
                    snapshot.stateDurationMs < STALE_STARTING_MS) ||
            (snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION &&
                    snapshot.stateDurationMs < STALE_WAITING_PERMISSION_MS)
        ) {
            return
        }

        try {
            AutoCapturePauseStore.resume(this)

            if (activeOperationId.isBlank()) {
                activeOperationId = AgentOperationStore.begin(
                    type = AgentOperationStore.TYPE_CAPTURE_START,
                    reason = reason
                )
            }

            isWaitingForPermission = true
            lastRequestAtElapsedMs = SystemClock.elapsedRealtime()

            CaptureStateStore.markWaitingPermission(activeOperationId)
            AgentOperationStore.markWaitingPermission(
                activeOperationId,
                "MainActivity MediaProjection consent UI opened"
            )

            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }

            handler.postDelayed(
                permissionTimeoutRunnable,
                PERMISSION_RESULT_TIMEOUT_MS
            )

            Log.i(
                TAG,
                "화면공유 권한 요청 실행. reason=$reason operationId=$activeOperationId"
            )

            startActivityForResult(captureIntent, REQUEST_CAPTURE_CODE)
        } catch (e: Exception) {
            isWaitingForPermission = false
            handler.removeCallbacks(permissionTimeoutRunnable)

            val message = e.message ?: "permission_activity_start_failed"
            CaptureStateStore.setError(message)
            AgentOperationStore.fail(
                activeOperationId,
                message,
                retryable = true
            )
            Log.e(TAG, "화면공유 권한 요청 실패. reason=$reason", e)
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE_CODE) return

        handler.removeCallbacks(permissionTimeoutRunnable)
        isWaitingForPermission = false
        isPermissionLaunchScheduled = false

        if (resultCode == RESULT_OK && data != null) {
            if (activeOperationId.isBlank() ||
                !AgentOperationStore.isPending(activeOperationId)
            ) {
                activeOperationId = AgentOperationStore.begin(
                    type = AgentOperationStore.TYPE_CAPTURE_START,
                    reason = "late_permission_result_main_activity"
                )
            }

            CaptureStateStore.activeOperationId = activeOperationId
            AgentOperationStore.markStarting(
                activeOperationId,
                "MediaProjection consent granted"
            )

            CaptureService.start(
                context = this,
                resultCode = resultCode,
                data = data,
                operationId = activeOperationId,
                reason = "MainActivity"
            )

            waitUntilCaptureRunningKeepUi(
                startedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        } else {
            AutoCapturePauseStore.pause(
                context = this,
                durationMs = DENIED_RETRY_PAUSE_MS,
                reason = "MEDIA_PROJECTION_DENIED"
            )

            CaptureStateStore.setError("media_projection_permission_denied")
            AgentOperationStore.fail(
                activeOperationId,
                "media_projection_permission_denied",
                retryable = true
            )
        }
    }

    private fun waitUntilCaptureRunningKeepUi(
        startedAtElapsedMs: Long
    ) {
        if (isUiHideInProgress) return

        val snapshot = CaptureStateStore.snapshot()
        val elapsedMs =
            SystemClock.elapsedRealtime() - startedAtElapsedMs

        val runningWithFrame =
            snapshot.state == CaptureStateStore.STATE_RUNNING &&
                    snapshot.encodedFrameCount > 0L &&
                    snapshot.frameAgeMs in 0..5_000L

        if (runningWithFrame) {
            /*
             * 중요:
             * 송출이 정상화돼도 MainActivity를 자동으로 닫거나
             * 뒤로 보내지 않습니다. 상태 텍스트만 "송출 중"으로 갱신합니다.
             */
            Log.i(
                TAG,
                "송출 RUNNING 확인. Agent UI를 유지합니다. " +
                        "frames=${snapshot.encodedFrameCount} " +
                        "frameAgeMs=${snapshot.frameAgeMs}"
            )

            updateStatusUI()
            return
        }

        if (elapsedMs >= WAIT_RUNNING_TIMEOUT_MS) {
            val message = "capture_first_frame_timeout"
            CaptureStateStore.setError(message)
            AgentOperationStore.fail(
                activeOperationId,
                message,
                retryable = true
            )
            CaptureService.stop(this)
            return
        }

        handler.postDelayed(
            { waitUntilCaptureRunningKeepUi(startedAtElapsedMs) },
            WAIT_RUNNING_INTERVAL_MS
        )
    }

    /**
     * 사용자가 "창 숨기기" 또는 X 버튼을 직접 누른 경우에만 호출됩니다.
     * 송출 RUNNING 진입 시에는 자동 호출하지 않습니다.
     */
    private fun hideAgentUiToBackground() {
        if (isUiHideInProgress) return

        isUiHideInProgress = true
        AgentUiState.isMainActivityVisible = false
        suppressAutoStartUntilElapsedMs =
            SystemClock.elapsedRealtime() + SUPPRESS_AUTO_START_MS

        AgentService.start(this)
        KeepAliveController.acquire(this)

        try {
            moveTaskToBack(true)
        } catch (e: Exception) {
            Log.w(TAG, "moveTaskToBack 실패", e)
        }

        handler.postDelayed({ removeAgentUiTask() }, 150L)
    }

    private fun removeAgentUiTask() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        } catch (e: Exception) {
            Log.w(TAG, "finishAndRemoveTask 실패, finish로 대체", e)
            try {
                finish()
            } catch (_: Exception) {
            }
        }

        try {
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
        }
    }

    private fun forceRestartStreaming() {
        AutoCapturePauseStore.resume(this)
        isWaitingForPermission = false
        isPermissionLaunchScheduled = false
        activeOperationId = ""
        lastRequestAtElapsedMs = 0L

        CaptureService.stop(this)
        waitForCaptureStopThenRestart(
            startedAtElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    private fun waitForCaptureStopThenRestart(
        startedAtElapsedMs: Long
    ) {
        if (isFinishing || isDestroyed || isUiHideInProgress) return

        val fullyStopped =
            !CaptureService.isServiceAlive &&
                    !CaptureService.isCleanupInProgress

        if (fullyStopped) {
            CaptureStateStore.clearToIdle()
            scheduleCapturePermissionRequest(
                delayMs = 250L,
                reason = "manual_force_restart"
            )
            return
        }

        val elapsed =
            SystemClock.elapsedRealtime() - startedAtElapsedMs

        if (elapsed >= 8_000L) {
            CaptureStateStore.setError("capture_cleanup_timeout")
            Log.w(TAG, "수동 송출 재시작 실패: capture_cleanup_timeout")
            return
        }

        handler.postDelayed(
            { waitForCaptureStopThenRestart(startedAtElapsedMs) },
            100L
        )
    }

    private fun stopStreamingManually() {
        AutoCapturePauseStore.pauseIndefinitely(
            context = this,
            reason = "MANUAL_STOP"
        )
        CaptureService.stop(this)
        CaptureStateStore.markStopped()
    }

    private fun createContentView(): View {
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.parseColor("#F5F5F5"))

            addView(TextView(this@MainActivity).apply {
                text = "MultiQuest Agent"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
            })

            nameInput = EditText(this@MainActivity).apply {
                hint = "기기 식별 이름"
                setText(AgentNameStore.getDeviceName(this@MainActivity))
                gravity = Gravity.CENTER

                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        AgentNameStore.setDeviceName(
                            this@MainActivity,
                            s?.toString().orEmpty()
                        )
                    }

                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) = Unit
                })
            }
            addView(nameInput)

            statusTextView = TextView(this@MainActivity).apply {
                text = "상태 확인 중..."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 30)
                setTextColor(Color.BLACK)
            }
            addView(statusTextView)

            addView(createStyledButton("수동 시작") {
                requestScreenCapturePermission()
            })

            addView(createStyledButton("송출 재시작") {
                forceRestartStreaming()
            }.apply {
                setTextColor(Color.parseColor("#1976D2"))
            })

            addView(createStyledButton("송출 중지") {
                stopStreamingManually()
            }.apply {
                setTextColor(Color.RED)
            })

            addView(createStyledButton("배터리 최적화 제외 설정") {
                ensureBatteryOptimized()
            })

            addView(createStyledButton("창 숨기기") {
                hideAgentUiToBackground()
            }.apply {
                setTextColor(Color.parseColor("#555555"))
            })
        }

        val versionTextView = TextView(this).apply {
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                "1.4.0"
            }

            text = "v$versionName"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, 30, 30)
            }
        }

        root.addView(mainLayout)
        root.addView(versionTextView)
        root.addView(createTopRightCloseButton())
        return root
    }

    private fun createTopRightCloseButton(): View {
        return TextView(this).apply {
            text = "×"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC333333"))
            contentDescription = "창 숨기기"
            isClickable = true
            isFocusable = true
            elevation = 16f
            setOnClickListener { hideAgentUiToBackground() }
            layoutParams = FrameLayout.LayoutParams(
                CLOSE_BUTTON_SIZE_PX,
                CLOSE_BUTTON_SIZE_PX
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(
                    0,
                    CLOSE_BUTTON_MARGIN_PX,
                    CLOSE_BUTTON_MARGIN_PX,
                    0
                )
            }
        }
    }

    private fun createStyledButton(
        textValue: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = textValue
            setOnClickListener { action() }

            val halfWidth =
                (resources.displayMetrics.widthPixels / 2)
                    .coerceAtLeast(320)

            layoutParams = LinearLayout.LayoutParams(
                halfWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 8, 0, 8)
            }
        }
    }

    private fun updateStatusUI() {
        val snapshot = CaptureStateStore.snapshot()

        statusTextView.text = when {
            isWaitingForPermission ||
                    snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION -> {
                "◐ 권한 승인 대기 중"
            }

            isPermissionLaunchScheduled -> "◐ 화면공유 요청 준비 중"
            snapshot.state == CaptureStateStore.STATE_STARTING -> "◐ 서비스 준비 중"

            CaptureService.isServiceAlive &&
                    snapshot.state == CaptureStateStore.STATE_RUNNING -> {
                if (snapshot.frameAgeMs > 3_000L) {
                    "● 송출 지연 (${snapshot.frameAgeMs}ms)"
                } else {
                    "● 송출 중"
                }
            }

            snapshot.state == CaptureStateStore.STATE_STOPPED -> "○ 수동 중지"
            snapshot.state == CaptureStateStore.STATE_IDLE -> "○ 대기 중"
            snapshot.state == CaptureStateStore.STATE_ERROR -> {
                "○ 송출 오류: ${snapshot.lastErrorMessage.ifBlank { "unknown" }}"
            }
            snapshot.state == CaptureStateStore.STATE_FROZEN -> "○ 송출 복구 중"
            else -> "○ 송출 정지됨"
        }

        statusTextView.setTextColor(
            when {
                isWaitingForPermission ||
                        isPermissionLaunchScheduled ||
                        snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION ||
                        snapshot.state == CaptureStateStore.STATE_STARTING -> {
                    Color.parseColor("#F57C00")
                }

                CaptureService.isServiceAlive &&
                        snapshot.state == CaptureStateStore.STATE_RUNNING -> {
                    Color.parseColor("#2E7D32")
                }

                snapshot.state == CaptureStateStore.STATE_IDLE ||
                        snapshot.state == CaptureStateStore.STATE_FROZEN -> {
                    Color.parseColor("#F57C00")
                }

                else -> Color.RED
            }
        )
    }

    private fun ensureBatteryOptimized() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "배터리 최적화 제외 요청 실패", e)
        }
    }

    /** 요청을 시작했으면 true를 반환합니다. */
    private fun requestPostNotificationsIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) return false

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_CODE
        )
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATION_CODE) {
            notificationPermissionRequestInFlight = false
            scheduleAutoStartCheck(
                source = "notification_permission_result",
                delayMs = 500L
            )
        }
    }

    override fun onPause() {
        AgentUiState.isMainActivityVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        if (currentRef?.get() === this) {
            currentRef = null
        }

        AgentUiState.isMainActivityVisible = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        hideAgentUiToBackground()
    }
}