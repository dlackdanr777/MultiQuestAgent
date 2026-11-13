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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import tdg.oculuswirelessadb.agent.AgentNameStore
import tdg.oculuswirelessadb.agent.AgentOperationStore
import tdg.oculuswirelessadb.agent.AgentService
import tdg.oculuswirelessadb.agent.AgentUiState
import tdg.oculuswirelessadb.agent.AutoCapturePauseStore
import tdg.oculuswirelessadb.agent.AppLaunchGuard
import tdg.oculuswirelessadb.agent.KeepAliveController
import tdg.oculuswirelessadb.agent.SystemSettingsLauncher
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

        // 물리적 공간 기록 삭제 화면을 확인하는 동안 자동 캡처 권한창이 다시 전면으로 뜨지 않게 합니다.
        private const val PHYSICAL_SPACE_SETTINGS_SUPPRESS_MS = 10 * 60 * 1000L

        private const val CLOSE_BUTTON_SIZE_PX = 48
        private const val CLOSE_BUTTON_MARGIN_PX = 14

        private var currentRef: WeakReference<MainActivity>? = null
        private val companionHandler = Handler(Looper.getMainLooper())

        /** WPF 종료 명령에서 Agent 설정 UI task를 제거합니다. */
        fun finishCurrent() {
            val action = Runnable {
                val activity = currentRef?.get()

                try {
                    if (
                        activity != null &&
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

            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run()
            } else {
                companionHandler.post(action)
            }
        }

        /**
         * 호환용 방어 함수입니다.
         * AgentCommandHandler의 정상 launch 경로에서는 호출하지 않습니다.
         * 외부 앱 세션 중 MainActivity가 예외적으로 전면화된 경우에만 plain finish합니다.
         */
        fun finishCurrentForExternalLaunchNow() {
            val action = Runnable {
                currentRef
                    ?.get()
                    ?.finishForExternalLaunchNow(
                        "external_storywing_launch"
                    )
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run()
            } else {
                companionHandler.post(action)
            }
        }

        fun finishCurrentForExternalLaunch() {
            finishCurrentForExternalLaunchNow()
        }

        /**
         * Meta Quest VRShell native launch Intent를 살아 있는 MainActivity에서 전송합니다.
         *
         * Lightning Launcher가 Quest VR 앱을 실행하는 방식과 동일하게:
         *
         * launcherActivity.startActivity(vrShellIntent)
         *
         * 를 사용합니다.
         *
         * 중요:
         * - VRShell Intent의 FLAG_ACTIVITY_NEW_TASK를 제거하지 않습니다.
         * - applicationContext fallback을 사용하지 않습니다.
         * - Activity source record가 없는 실행을 허용하지 않습니다.
         *
         * applicationContext/Service에서 VRShell을 실행하면 Quest 로그에
         * sourceRecord=null 또는 launch source=(unknown)이 남을 수 있고,
         * 앱은 FOCUSED/72FPS여도 APP_START 전환 세션이 끝나지 않을 수 있습니다.
         */
        fun launchVrShellIntentNow(
            sourceIntent: Intent
        ): Boolean {
            if (
                Looper.myLooper() !=
                Looper.getMainLooper()
            ) {
                Log.e(
                    TAG,
                    "launchVrShellIntentNow must run on Main Looper"
                )

                return false
            }

            val activity =
                currentRef?.get()

            if (
                activity == null ||
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                Log.e(
                    TAG,
                    "No live MainActivity source for VRShell launch. " +
                            "Open the Agent UI once before remote launching."
                )

                return false
            }

            val vrShellIntent =
                Intent(
                    sourceIntent
                )

            return try {
                activity.startActivity(
                    vrShellIntent
                )

                Log.i(
                    TAG,
                    "VRShell immersive launch dispatched from MainActivity. " +
                            "route=MAIN_ACTIVITY_VRSHELL_SOURCE " +
                            "component=${vrShellIntent.component} " +
                            "action=${vrShellIntent.action} " +
                            "data=${vrShellIntent.data} " +
                            "flags=0x${vrShellIntent.flags.toString(16)}"
                )

                true
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "MainActivity VRShell launch failed. " +
                            "component=${vrShellIntent.component} " +
                            "action=${vrShellIntent.action} " +
                            "data=${vrShellIntent.data}",
                    e
                )

                false
            }
        }

        /**
         * 기존 일반 Activity 직접 실행 호출부의 호환용입니다.
         *
         * VRShell native launch에는 launchVrShellIntentNow()를 사용해야 합니다.
         */
        fun launchExternalActivityNow(
            sourceIntent: Intent
        ): Boolean {
            if (
                Looper.myLooper() !=
                Looper.getMainLooper()
            ) {
                Log.e(
                    TAG,
                    "launchExternalActivityNow must run on Main Looper"
                )

                return false
            }

            val activity =
                currentRef?.get()

            if (
                activity == null ||
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                Log.w(
                    TAG,
                    "No live MainActivity source for external launch"
                )

                return false
            }

            val activityIntent =
                Intent(
                    sourceIntent
                ).apply {
                    flags =
                        flags and
                                Intent.FLAG_ACTIVITY_NEW_TASK.inv()

                    flags =
                        flags and
                                Intent.FLAG_ACTIVITY_CLEAR_TASK.inv()

                    flags =
                        flags and
                                Intent.FLAG_ACTIVITY_CLEAR_TOP.inv()

                    flags =
                        flags and
                                Intent.FLAG_ACTIVITY_SINGLE_TOP.inv()
                }

            return try {
                activity.startActivity(
                    activityIntent
                )

                Log.i(
                    TAG,
                    "External Activity launch dispatched from MainActivity. " +
                            "component=${activityIntent.component} " +
                            "action=${activityIntent.action} " +
                            "flags=0x${activityIntent.flags.toString(16)}"
                )

                true
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "MainActivity external launch failed. " +
                            "component=${activityIntent.component} " +
                            "action=${activityIntent.action}",
                    e
                )

                false
            }
        }

        /**
         * STOP/STOP_ALL에서 새 Activity를 띄우지 않고 Agent UI task만 뒤로 보냅니다.
         *
         * finish/removeTask 하지 않으므로 MainActivity 인스턴스는 살아 있고,
         * 다음 원격 launch에서 VRShell의 실제 Activity source로 재사용됩니다.
         *
         * IdleShieldActivity 같은 2D panel Activity를 새로 띄우지 않기 때문에
         * 종료 후 Quest volumetric panel/UI 전환 경쟁도 만들지 않습니다.
         */
        fun moveTaskToBackForRemoteStopNow(
            reason: String
        ): Boolean {
            if (
                Looper.myLooper() !=
                Looper.getMainLooper()
            ) {
                Log.e(
                    TAG,
                    "moveTaskToBackForRemoteStopNow must run on Main Looper"
                )

                return false
            }

            val activity =
                currentRef?.get()

            if (
                activity == null ||
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                Log.w(
                    TAG,
                    "No live MainActivity to background. reason=$reason"
                )

                return false
            }

            return try {
                AgentUiState.isMainActivityVisible =
                    false

                val moved =
                    activity.moveTaskToBack(
                        true
                    )

                Log.i(
                    TAG,
                    "Agent MainActivity moved to background. " +
                            "reason=$reason moved=$moved"
                )

                moved
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Failed to move Agent task to background. " +
                            "reason=$reason",
                    e
                )

                false
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

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "MainActivity suppressed by StoryWing launch guard. " +
                        "package=${AppLaunchGuard.packageName()}"
            )

            finishForExternalLaunchNow(
                "onCreate_launch_guard"
            )
            return
        }

        // 업데이트 후 여러 저장소 중 하나에만 이름이 남은 경우 자동 복구합니다.
        AgentNameStore.repair(this)

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

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "MainActivity new Intent suppressed by StoryWing launch guard. " +
                        "package=${AppLaunchGuard.packageName()}"
            )

            finishForExternalLaunchNow(
                "onNewIntent_launch_guard"
            )
            return
        }

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

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "MainActivity resume suppressed by StoryWing launch guard. " +
                        "package=${AppLaunchGuard.packageName()}"
            )

            finishForExternalLaunchNow(
                "onResume_launch_guard"
            )
            return
        }

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
        if (AppLaunchGuard.isActive()) {
            Log.d(
                TAG,
                "Auto capture check skipped by StoryWing launch guard. " +
                        "source=$source package=${AppLaunchGuard.packageName()}"
            )
            return
        }

        handler.postDelayed(
            {
                if (AppLaunchGuard.isActive()) {
                    Log.d(
                        TAG,
                        "Delayed auto capture check cancelled by launch guard. " +
                                "source=$source package=${AppLaunchGuard.packageName()}"
                    )
                    return@postDelayed
                }

                checkAndAutoStart(source)
            },
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
        if (AppLaunchGuard.isActive()) {
            Log.d(
                TAG,
                "Auto capture blocked by StoryWing launch guard. " +
                        "source=$source package=${AppLaunchGuard.packageName()}"
            )
            return
        }

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
        if (AppLaunchGuard.isActive()) {
            Log.d(
                TAG,
                "Capture permission scheduling blocked by StoryWing launch guard. " +
                        "reason=$reason package=${AppLaunchGuard.packageName()}"
            )
            return false
        }

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

            if (AppLaunchGuard.isActive()) {
                Log.d(
                    TAG,
                    "Scheduled capture permission cancelled by launch guard. " +
                            "reason=$reason package=${AppLaunchGuard.packageName()}"
                )
                return@postDelayed
            }

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
        if (AppLaunchGuard.isActive()) {
            Log.d(
                TAG,
                "Capture permission request blocked by StoryWing launch guard. " +
                        "reason=$reason package=${AppLaunchGuard.packageName()}"
            )
            return
        }

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

        if (AppLaunchGuard.isActive()) {
            Log.w(
                TAG,
                "Late MediaProjection result ignored during external app session. " +
                        "package=${AppLaunchGuard.packageName()}"
            )
            isWaitingForPermission = false
            isPermissionLaunchScheduled = false
            handler.removeCallbacks(permissionTimeoutRunnable)
            return
        }

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
     * StoryWing/OpenXR 앱 실행 보호 중에는 Agent 설정 UI가 전면 포커스를
     * 다시 가져가지 않도록 현재 Activity만 plain finish합니다.
     */
    private fun finishForExternalLaunchNow(
        reason: String
    ) {
        if (isFinishing || isDestroyed) {
            return
        }

        isUiHideInProgress = true
        isWaitingForPermission = false
        isPermissionLaunchScheduled = false
        notificationPermissionRequestInFlight = false
        suppressAutoStartUntilElapsedMs = Long.MAX_VALUE
        AgentUiState.isMainActivityVisible = false
        handler.removeCallbacksAndMessages(null)

        Log.i(
            TAG,
            "Agent MainActivity closed for external StoryWing launch. " +
                    "reason=$reason package=${AppLaunchGuard.packageName()}"
        )

        try {
            finish()
        } catch (_: Exception) {
        }

        try {
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
        }
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

    /**
     * Agent UI의 "룸스캔 데이터 삭제" 버튼 동작입니다.
     * AgentService와 HTTP 서버는 종료하지 않습니다.
     */
    private fun openPhysicalSpaceHistorySettingsFromButton() {
        if (isFinishing || isDestroyed) return

        // 설정을 보고 있는 동안 자동 화면공유 복구 UI가 덮어쓰지 않도록 억제합니다.
        suppressAutoStartUntilElapsedMs =
            SystemClock.elapsedRealtime() + PHYSICAL_SPACE_SETTINGS_SUPPRESS_MS

        try {
            AgentService.start(this)
            KeepAliveController.acquire(this)
        } catch (e: Exception) {
            Log.w(TAG, "Agent 유지 요청 실패", e)
        }

        val result = SystemSettingsLauncher.openPhysicalSpaceHistory(this)

        if (result.opened) {
            Log.w(
                TAG,
                "물리적 공간 기록 삭제 화면 실행 요청 성공. uri=${result.uri}"
            )

            Toast.makeText(
                applicationContext,
                "물리적 공간 기록 삭제 항목을 누르세요.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Log.e(
                TAG,
                "물리적 공간 기록 삭제 화면 실행 실패. error=${result.error}"
            )

            Toast.makeText(
                applicationContext,
                "공간 데이터 삭제 화면 실행 실패: ${result.error}",
                Toast.LENGTH_LONG
            ).show()
        }
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

            // 공간 데이터 삭제 관련 설정은 일반 송출 제어 버튼 아래에 배치합니다.
            addView(createStyledButton("방 데이터 삭제 설정 열기") {
                openPhysicalSpaceHistorySettingsFromButton()
            }.apply {
                setTextColor(Color.parseColor("#D84315"))
                contentDescription = "Meta Quest 물리적 공간 기록 삭제 설정 열기"
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
        try {
            if (::nameInput.isInitialized) {
                AgentNameStore.setDeviceName(
                    this,
                    nameInput.text?.toString().orEmpty()
                )
            }
        } catch (_: Exception) {
        }

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