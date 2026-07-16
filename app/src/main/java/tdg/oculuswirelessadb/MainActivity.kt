package tdg.oculuswirelessadb

import android.Manifest
import android.app.ActivityManager
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
import tdg.oculuswirelessadb.agent.AgentService
import tdg.oculuswirelessadb.agent.AgentUiState
import tdg.oculuswirelessadb.agent.KeepAliveController
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val REQUEST_CAPTURE_CODE = 1000
        private const val REQUEST_NOTIFICATION_CODE = 1001

        private const val AUTO_CAPTURE_DELAY_MS = 300L
        private const val MIN_CAPTURE_REQUEST_INTERVAL_MS = 1_500L

        private const val WAIT_RUNNING_TIMEOUT_MS = 10_000L
        private const val WAIT_RUNNING_INTERVAL_MS = 250L

        private const val SUPPRESS_AUTO_START_MS = 5_000L

        // 이전 96px 버튼의 절반 크기
        private const val CLOSE_BUTTON_SIZE_PX = 48
        private const val CLOSE_BUTTON_MARGIN_PX = 14
    }

    private lateinit var statusTextView: TextView
    private lateinit var nameInput: EditText
    private lateinit var projectionManager: MediaProjectionManager

    private val handler = Handler(Looper.getMainLooper())

    private var isWaitingForPermission = false
    private var isPermissionLaunchScheduled = false
    private var isUiHideInProgress = false

    private var lastRequestTime = 0L
    private var suppressAutoStartUntilMs = 0L

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

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0.0f)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        requestPostNotificationsIfNeeded()

        // UI 태스크가 제거되어도 AgentService는 계속 실행됩니다.
        AgentService.start(this)
        KeepAliveController.acquire(this)

        setContentView(createContentView())

        handler.post(statusUpdater)

        consumeSuppressAutoStartFlag()

        if (shouldAutoCaptureRequest() && isScreenInteractive()) {
            setupShowOnLockScreen()
        }

        if (!shouldAutoCaptureRequest()) {
            handler.postDelayed({
                ensureBatteryOptimized()
            }, 1_500L)
        }

        handler.postDelayed({
            checkAndAutoStart(source = "onCreate")
        }, AUTO_CAPTURE_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        consumeSuppressAutoStartFlag()

        if (shouldAutoCaptureRequest() && isScreenInteractive()) {
            setupShowOnLockScreen()
        }

        checkAndAutoStart(source = "onNewIntent")
    }

    override fun onResume() {
        super.onResume()

        isUiHideInProgress = false

        AgentUiState.isMainActivityVisible = true

        AgentService.start(this)
        KeepAliveController.acquire(this)

        consumeSuppressAutoStartFlag()

        checkAndAutoStart(source = "onResume")
    }

    private fun consumeSuppressAutoStartFlag() {
        val suppress =
            intent?.getBooleanExtra(
                "suppressAutoStartOnce",
                false
            ) == true

        if (suppress) {
            suppressAutoStartUntilMs =
                System.currentTimeMillis() + SUPPRESS_AUTO_START_MS

            intent?.removeExtra("suppressAutoStartOnce")

            Log.i(TAG, "자동 화면공유 시작 임시 억제 적용")
        }
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val pm =
                getSystemService(Context.POWER_SERVICE) as PowerManager

            pm.isInteractive
        } catch (_: Exception) {
            false
        }
    }

    private fun shouldAutoCaptureRequest(): Boolean {
        return intent
            ?.getBooleanExtra("requestCapture", false) == true
    }

    private fun setupShowOnLockScreen() {
        if (!isScreenInteractive()) {
            return
        }

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
        if (isUiHideInProgress) {
            return
        }

        if (!isScreenInteractive()) {
            Log.d(
                TAG,
                "checkAndAutoStart skipped: screen off. source=$source"
            )
            return
        }

        val now = System.currentTimeMillis()

        if (now < suppressAutoStartUntilMs) {
            Log.d(
                TAG,
                "checkAndAutoStart skipped: suppress window. source=$source"
            )
            return
        }

        val isAutoRequest = shouldAutoCaptureRequest()
        val state = CaptureStateStore.state

        if (isAutoRequest) {
            intent?.removeExtra("requestCapture")
        }

        if (isWaitingForPermission || isPermissionLaunchScheduled) {
            return
        }

        if (state == CaptureStateStore.STATE_RUNNING ||
            state == CaptureStateStore.STATE_STARTING ||
            state == CaptureStateStore.STATE_WAITING_PERMISSION
        ) {
            return
        }

        val shouldStartFromNormalOpen =
            state == CaptureStateStore.STATE_STOPPED ||
                    state == CaptureStateStore.STATE_IDLE ||
                    state.isBlank()

        if (!isAutoRequest && !shouldStartFromNormalOpen) {
            return
        }

        if (now - lastRequestTime < MIN_CAPTURE_REQUEST_INTERVAL_MS) {
            return
        }

        Log.i(
            TAG,
            "자동 화면공유 시작 예약: " +
                    "source=$source auto=$isAutoRequest state=$state"
        )

        scheduleCapturePermissionRequest(
            delayMs = AUTO_CAPTURE_DELAY_MS,
            reason = "auto_$source"
        )
    }

    private fun scheduleCapturePermissionRequest(
        delayMs: Long,
        reason: String
    ) {
        if (isUiHideInProgress) {
            return
        }

        if (!isScreenInteractive()) {
            return
        }

        if (isWaitingForPermission || isPermissionLaunchScheduled) {
            return
        }

        val state = CaptureStateStore.state

        if (state == CaptureStateStore.STATE_RUNNING ||
            state == CaptureStateStore.STATE_STARTING ||
            state == CaptureStateStore.STATE_WAITING_PERMISSION
        ) {
            return
        }

        isPermissionLaunchScheduled = true
        lastRequestTime = System.currentTimeMillis()

        handler.postDelayed({
            isPermissionLaunchScheduled = false

            if (!isUiHideInProgress) {
                requestScreenCapturePermissionInternal(reason)
            }
        }, delayMs)
    }

    private fun requestScreenCapturePermission() {
        scheduleCapturePermissionRequest(
            delayMs = 0L,
            reason = "manual_button"
        )
    }

    private fun requestScreenCapturePermissionInternal(reason: String) {
        if (isUiHideInProgress) {
            return
        }

        if (!isScreenInteractive()) {
            return
        }

        if (isWaitingForPermission) {
            return
        }

        val state = CaptureStateStore.state

        if (state == CaptureStateStore.STATE_RUNNING ||
            state == CaptureStateStore.STATE_STARTING ||
            state == CaptureStateStore.STATE_WAITING_PERMISSION
        ) {
            return
        }

        try {
            isWaitingForPermission = true
            lastRequestTime = System.currentTimeMillis()

            CaptureStateStore.markWaitingPermission()

            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }

            Log.i(
                TAG,
                "화면공유 권한 요청 실행. reason=$reason"
            )

            startActivityForResult(
                captureIntent,
                REQUEST_CAPTURE_CODE
            )
        } catch (e: Exception) {
            isWaitingForPermission = false

            CaptureStateStore.clearToIdle()

            Log.e(
                TAG,
                "화면공유 권한 요청 실패. reason=$reason",
                e
            )
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode != REQUEST_CAPTURE_CODE) {
            return
        }

        isWaitingForPermission = false
        isPermissionLaunchScheduled = false

        if (resultCode == RESULT_OK && data != null) {
            Log.i(
                TAG,
                "권한 획득 성공 - CaptureService 시작"
            )

            CaptureService.start(
                context = this,
                resultCode = resultCode,
                data = data
            )

            waitUntilCaptureRunningThenHide(
                startedAtMs = System.currentTimeMillis()
            )
        } else {
            Log.w(TAG, "화면공유 권한 거부됨")
            CaptureStateStore.clearToIdle()
        }
    }

    /**
     * MainActivity에서 수동 또는 자동으로 화면공유를 시작한 경우에도
     * 저장된 앱을 startActivity()로 다시 실행하지 않습니다.
     *
     * 화면공유가 정상화되면 Agent UI 태스크만 제거합니다.
     * 그러면 Quest가 실제로 살아 있는 이전 태스크를 선택합니다.
     */
    private fun waitUntilCaptureRunningThenHide(startedAtMs: Long) {
        if (isUiHideInProgress) {
            return
        }

        val state = CaptureStateStore.state
        val frames = CaptureStateStore.encodedFrameCount
        val frameAgeMs = CaptureStateStore.getFrameAgeMs()
        val elapsedMs =
            System.currentTimeMillis() - startedAtMs

        val runningWithFrame =
            state == CaptureStateStore.STATE_RUNNING &&
                    frames > 0L &&
                    frameAgeMs in 0..5_000L

        if (runningWithFrame) {
            Log.i(
                TAG,
                "송출 RUNNING 확인. " +
                        "마지막 앱을 다시 실행하지 않고 Agent UI를 제거합니다. " +
                        "frames=$frames frameAgeMs=$frameAgeMs"
            )

            hideAgentUiToBackground()
            return
        }

        if (elapsedMs >= WAIT_RUNNING_TIMEOUT_MS) {
            Log.w(
                TAG,
                "송출 RUNNING 대기 타임아웃. " +
                        "Agent UI는 유지합니다. " +
                        "state=$state frames=$frames frameAgeMs=$frameAgeMs"
            )

            return
        }

        handler.postDelayed({
            waitUntilCaptureRunningThenHide(startedAtMs)
        }, WAIT_RUNNING_INTERVAL_MS)
    }

    /**
     * Agent UI만 제거하고 AgentService는 계속 실행합니다.
     *
     * finishAndRemoveTask()는 Activity 태스크만 제거하며,
     * foreground AgentService / 와치독 / HTTP 서버는 종료하지 않습니다.
     */
    private fun hideAgentUiToBackground() {
        if (isUiHideInProgress) {
            return
        }

        isUiHideInProgress = true

        Log.i(TAG, "Agent UI 백그라운드 이동 요청")

        AgentUiState.isMainActivityVisible = false

        suppressAutoStartUntilMs =
            System.currentTimeMillis() + SUPPRESS_AUTO_START_MS

        try {
            AgentService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "AgentService 유지 요청 실패", e)
        }

        try {
            KeepAliveController.acquire(this)
        } catch (e: Exception) {
            Log.w(TAG, "KeepAlive 유지 요청 실패", e)
        }

        val moved = try {
            moveTaskToBack(true)
        } catch (e: Exception) {
            Log.w(TAG, "moveTaskToBack 실패", e)
            false
        }

        Log.i(TAG, "moveTaskToBack 결과=$moved")

        handler.postDelayed({
            removeAgentUiTask()
        }, 150L)
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
        CaptureService.stop(this)
        CaptureStateStore.clearToIdle()

        isWaitingForPermission = false
        isPermissionLaunchScheduled = false
        lastRequestTime = 0L

        scheduleCapturePermissionRequest(
            delayMs = 1_200L,
            reason = "manual_force_restart"
        )
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

            setPadding(
                64,
                64,
                64,
                64
            )

            setBackgroundColor(
                Color.parseColor("#F5F5F5")
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = "MultiQuest Agent"
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.BLACK)
                }
            )

            nameInput = EditText(this@MainActivity).apply {
                hint = "기기 식별 이름"

                setText(
                    AgentNameStore.getDeviceName(this@MainActivity)
                )

                gravity = Gravity.CENTER

                addTextChangedListener(
                    object : TextWatcher {
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
                        ) {
                        }

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                        }
                    }
                )
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

            addView(
                createStyledButton("수동 시작") {
                    requestScreenCapturePermission()
                }
            )

            addView(
                createStyledButton("송출 재시작") {
                    forceRestartStreaming()
                }.apply {
                    setTextColor(
                        Color.parseColor("#1976D2")
                    )
                }
            )

            addView(
                createStyledButton("송출 중지") {
                    CaptureService.stop(this@MainActivity)
                    CaptureStateStore.markStopped()
                }.apply {
                    setTextColor(Color.RED)
                }
            )

            addView(
                createStyledButton("창 숨기기") {
                    hideAgentUiToBackground()
                }.apply {
                    setTextColor(
                        Color.parseColor("#555555")
                    )
                }
            )
        }

        val versionTextView = TextView(this).apply {
            val versionName = try {
                packageManager
                    .getPackageInfo(packageName, 0)
                    .versionName
            } catch (_: Exception) {
                "1.3.0"
            }

            text = "v$versionName"
            textSize = 12f

            setTextColor(
                Color.parseColor("#888888")
            )

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, 30, 30)
            }
        }

        val closeButton = createTopRightCloseButton()

        root.addView(mainLayout)
        root.addView(versionTextView)
        root.addView(closeButton)

        return root
    }

    /**
     * 우측 상단 작은 X 버튼.
     * 이전 96px의 절반인 48px 크기입니다.
     */
    private fun createTopRightCloseButton(): View {
        return TextView(this).apply {
            text = "×"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER

            setTextColor(Color.WHITE)

            setBackgroundColor(
                Color.parseColor("#CC333333")
            )

            contentDescription = "창 숨기기"

            isClickable = true
            isFocusable = true

            elevation = 16f

            setOnClickListener {
                hideAgentUiToBackground()
            }

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

    /**
     * 일반 버튼 폭을 Quest 화면 폭의 약 1/2로 제한합니다.
     */
    private fun createStyledButton(
        textValue: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = textValue

            setOnClickListener {
                action()
            }

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
        val captureServiceRunning =
            checkServiceRunning(CaptureService::class.java)

        val state = CaptureStateStore.state
        val frameAgeMs = CaptureStateStore.getFrameAgeMs()

        statusTextView.text = when {
            isWaitingForPermission ||
                    state == CaptureStateStore.STATE_WAITING_PERMISSION -> {
                "◐ 권한 승인 대기 중"
            }

            isPermissionLaunchScheduled -> {
                "◐ 화면공유 요청 준비 중"
            }

            state == CaptureStateStore.STATE_STARTING -> {
                "◐ 서비스 준비 중"
            }

            captureServiceRunning &&
                    state == CaptureStateStore.STATE_RUNNING -> {
                if (frameAgeMs > 3_000L) {
                    "● 송출 지연 (${frameAgeMs}ms)"
                } else {
                    "● 송출 중"
                }
            }

            state == CaptureStateStore.STATE_STOPPED -> {
                "○ 기기 휴식 중"
            }

            state == CaptureStateStore.STATE_IDLE -> {
                "○ 대기 중"
            }

            state == CaptureStateStore.STATE_ERROR -> {
                "○ 송출 오류"
            }

            state == CaptureStateStore.STATE_FROZEN -> {
                "○ 송출 복구 중"
            }

            else -> {
                "○ 송출 정지됨"
            }
        }

        statusTextView.setTextColor(
            when {
                isWaitingForPermission ||
                        isPermissionLaunchScheduled ||
                        state == CaptureStateStore.STATE_WAITING_PERMISSION ||
                        state == CaptureStateStore.STATE_STARTING -> {
                    Color.parseColor("#F57C00")
                }

                captureServiceRunning &&
                        state == CaptureStateStore.STATE_RUNNING -> {
                    Color.parseColor("#2E7D32")
                }

                state == CaptureStateStore.STATE_IDLE ||
                        state == CaptureStateStore.STATE_FROZEN -> {
                    Color.parseColor("#F57C00")
                }

                else -> {
                    Color.RED
                }
            }
        )
    }

    private fun checkServiceRunning(
        serviceClass: Class<*>
    ): Boolean {
        val manager =
            getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        @Suppress("DEPRECATION")
        return manager
            .getRunningServices(Int.MAX_VALUE)
            .any {
                it.service.className == serviceClass.name
            }
    }

    private fun ensureBatteryOptimized() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val pm =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "배터리 최적화 제외 요청 실패",
                e
            )
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_CODE
            )
        }
    }

    override fun onPause() {
        AgentUiState.isMainActivityVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        hideAgentUiToBackground()
    }
}