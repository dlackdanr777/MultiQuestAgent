package tdg.oculuswirelessadb

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tdg.oculuswirelessadb.agent.AgentOperationStore
import tdg.oculuswirelessadb.agent.AgentService
import tdg.oculuswirelessadb.agent.AgentUiState
import tdg.oculuswirelessadb.agent.AutoCapturePauseStore
import tdg.oculuswirelessadb.agent.AppLaunchGuard
import tdg.oculuswirelessadb.agent.KeepAliveController
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore
import java.lang.ref.WeakReference

class CapturePermissionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CapturePermission"
        private const val REQUEST_CAPTURE = 1000

        private const val EXTRA_REASON = "captureReason"
        private const val EXTRA_OPERATION_ID = "captureOperationId"

        private const val REQUEST_DELAY_MS = 250L
        private const val PERMISSION_RESULT_TIMEOUT_MS = 30_000L
        private const val WAIT_RUNNING_TIMEOUT_MS = 25_000L
        private const val WAIT_RUNNING_INTERVAL_MS = 250L
        private const val STALE_WAITING_PERMISSION_MS = 35_000L
        private const val STALE_STARTING_MS = 30_000L
        private const val DENIED_RETRY_PAUSE_MS = 2 * 60 * 1000L

        private var currentRef: WeakReference<CapturePermissionActivity>? = null
        private val companionHandler = Handler(Looper.getMainLooper())

        /** 앱 종료 명령과 동시에 떠 있는 MediaProjection 권한 UI를 닫습니다. */
        fun finishCurrentForUiDisable() {
            val action = Runnable {
                currentRef
                    ?.get()
                    ?.finishForUiDisable()
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run()
            } else {
                companionHandler.post(action)
            }
        }

        /**
         * 외부 StoryWing 앱 실행 전에 이미 열려 있던 권한 Activity만 닫습니다.
         * launch 이후 반복 정리는 수행하지 않습니다.
         */
        fun finishCurrentForExternalLaunchNow() {
            val action = Runnable {
                currentRef
                    ?.get()
                    ?.finishForExternalLaunch()
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

        fun createIntent(
            context: Context,
            reason: String,
            operationId: String = ""
        ): Intent {
            return Intent(context, CapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_OPERATION_ID, operationId)
            }
        }
    }

    private lateinit var projectionManager: MediaProjectionManager

    private val handler = Handler(Looper.getMainLooper())

    private var requested = false
    private var resultHandled = false
    private var finishingCaptureTask = false
    private var reason: String = ""
    private var operationId: String = ""

    private val permissionTimeoutRunnable = Runnable {
        if (!requested || resultHandled || finishingCaptureTask) {
            return@Runnable
        }

        val message = "media_projection_permission_timeout"
        Log.w(TAG, "$message. reason=$reason operationId=$operationId")

        requested = false
        resultHandled = true
        CaptureStateStore.setError(message)
        AgentOperationStore.fail(
            operationId,
            message,
            retryable = true
        )

        finishCaptureTaskCleanly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentRef = WeakReference(this)
        readIntentData(intent)
        AgentUiState.isCapturePermissionActivityAlive = true

        /*
         * StoryWing/OpenXR 앱 실행 보호 구간에는 투명 권한 Activity도
         * 전면 포커스를 가져가면 안 됩니다.
         */
        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "CapturePermissionActivity suppressed by launch guard. " +
                        "package=${AppLaunchGuard.packageName()} " +
                        "reason=$reason operationId=$operationId"
            )

            cancelOperationForExternalLaunch()
            finishForExternalLaunch()
            return
        }

        makeWindowTransparent()

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        AgentService.start(this)
        KeepAliveController.acquire(this)

        setContentView(
            TextView(this).apply {
                text = ""
                gravity = Gravity.CENTER
                setTextColor(Color.TRANSPARENT)
                setBackgroundColor(Color.TRANSPARENT)
            }
        )

        if (operationId.isBlank()) {
            operationId = AgentOperationStore.begin(
                type = AgentOperationStore.TYPE_CAPTURE_START,
                reason = reason.ifBlank { "CapturePermissionActivity" }
            )
        }

        AgentOperationStore.markDispatched(
            operationId,
            "CapturePermissionActivity.onCreate"
        )

        if (!isScreenInteractive()) {
            failAndFinish(
                message = "device_not_interactive",
                retryable = true
            )
            return
        }

        handler.postDelayed(
            { requestCapturePermissionOnce() },
            REQUEST_DELAY_MS
        )
    }

    override fun onResume() {
        super.onResume()

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "CapturePermissionActivity resume suppressed by launch guard. " +
                        "package=${AppLaunchGuard.packageName()} " +
                        "operationId=$operationId"
            )

            cancelOperationForExternalLaunch()
            finishForExternalLaunch()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return

        val incomingOperationId = intent
            .getStringExtra(EXTRA_OPERATION_ID)
            .orEmpty()
        val incomingReason = intent
            .getStringExtra(EXTRA_REASON)
            .orEmpty()

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "CapturePermissionActivity new Intent suppressed by launch guard. " +
                        "package=${AppLaunchGuard.packageName()} " +
                        "incomingOperationId=$incomingOperationId"
            )

            if (
                incomingOperationId.isNotBlank() &&
                AgentOperationStore.isPending(incomingOperationId)
            ) {
                AgentOperationStore.cancel(
                    incomingOperationId,
                    "capture_ui_deferred_by_storywing_launch"
                )
            }

            cancelOperationForExternalLaunch()
            finishForExternalLaunch()
            return
        }

        if (requested && !resultHandled && !finishingCaptureTask) {
            if (incomingOperationId.isNotBlank() &&
                incomingOperationId != operationId
            ) {
                AgentOperationStore.fail(
                    incomingOperationId,
                    "capture_permission_already_in_progress",
                    retryable = true
                )
            }

            Log.i(
                TAG,
                "Duplicate capture request ignored while consent is active. " +
                        "currentOperationId=$operationId incomingOperationId=$incomingOperationId"
            )
            return
        }

        setIntent(intent)
        reason = incomingReason
        operationId = incomingOperationId

        if (!resultHandled && !finishingCaptureTask) {
            handler.postDelayed(
                { requestCapturePermissionOnce() },
                REQUEST_DELAY_MS
            )
        }
    }

    private fun readIntentData(intent: Intent?) {
        reason = intent
            ?.getStringExtra(EXTRA_REASON)
            .orEmpty()

        operationId = intent
            ?.getStringExtra(EXTRA_OPERATION_ID)
            .orEmpty()
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

    private fun makeWindowTransparent() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0.0f)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        if (
            isScreenInteractive() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        ) {
            try {
                setShowWhenLocked(true)
                setTurnScreenOn(true)

                val keyguardManager =
                    getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

                keyguardManager.requestDismissKeyguard(this, null)
            } catch (e: Exception) {
                Log.w(TAG, "잠금 화면 설정 실패", e)
            }
        }

        try {
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
        }
    }

    private fun requestCapturePermissionOnce() {
        if (requested || resultHandled || finishingCaptureTask) return

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "Capture permission request deferred by StoryWing launch. " +
                        "package=${AppLaunchGuard.packageName()} " +
                        "remainingMs=${AppLaunchGuard.remainingMs()} " +
                        "operationId=$operationId"
            )

            cancelOperationForExternalLaunch()
            finishForExternalLaunch()
            return
        }

        if (!isScreenInteractive()) {
            failAndFinish("device_not_interactive", retryable = true)
            return
        }

        val snapshot = CaptureStateStore.snapshot()

        if (
            snapshot.state == CaptureStateStore.STATE_RUNNING &&
            snapshot.encodedFrameCount > 0L &&
            snapshot.frameAgeMs in 0..5_000L
        ) {
            AgentOperationStore.complete(
                operationId,
                "capture_already_running"
            )
            finishCaptureTaskCleanly()
            return
        }

        if (
            snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION &&
            snapshot.stateDurationMs < STALE_WAITING_PERMISSION_MS
        ) {
            failOperationOnlyAndFinish(
                message = "another_permission_request_is_active",
                retryable = true
            )
            return
        }

        if (
            snapshot.state == CaptureStateStore.STATE_STARTING &&
            snapshot.stateDurationMs < STALE_STARTING_MS
        ) {
            failOperationOnlyAndFinish(
                message = "another_capture_start_is_active",
                retryable = true
            )
            return
        }

        if (
            snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION ||
            snapshot.state == CaptureStateStore.STATE_STARTING
        ) {
            CaptureStateStore.setError("stale_capture_start_state")
            CaptureService.stop(this)
        }

        try {
            AutoCapturePauseStore.resume(this)

            requested = true
            CaptureStateStore.markWaitingPermission(operationId)
            AgentOperationStore.markWaitingPermission(
                operationId,
                "MediaProjection consent UI opened"
            )

            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }

            Log.i(
                TAG,
                "화면공유 권한 요청 실행. reason=$reason operationId=$operationId"
            )

            handler.postDelayed(
                permissionTimeoutRunnable,
                PERMISSION_RESULT_TIMEOUT_MS
            )

            startActivityForResult(captureIntent, REQUEST_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "화면공유 권한 요청 실패. reason=$reason", e)
            failAndFinishAccordingToSource(
                message = e.message ?: "permission_activity_start_failed",
                retryable = true
            )
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE) return
        if (resultHandled || finishingCaptureTask) return

        if (AppLaunchGuard.isActive()) {
            Log.w(
                TAG,
                "Late MediaProjection result ignored during external app session. " +
                        "package=${AppLaunchGuard.packageName()} operationId=$operationId"
            )
            cancelOperationForExternalLaunch()
            finishForExternalLaunch()
            return
        }

        handler.removeCallbacks(permissionTimeoutRunnable)
        resultHandled = true
        requested = false

        if (resultCode == RESULT_OK && data != null) {
            if (operationId.isBlank() ||
                !AgentOperationStore.isPending(operationId)
            ) {
                operationId = AgentOperationStore.begin(
                    type = AgentOperationStore.TYPE_CAPTURE_START,
                    reason = "late_permission_result_$reason"
                )
            }

            Log.i(
                TAG,
                "화면공유 권한 획득 성공. reason=$reason operationId=$operationId"
            )

            CaptureStateStore.activeOperationId = operationId
            AgentOperationStore.markStarting(
                operationId,
                "MediaProjection consent granted"
            )

            CaptureService.start(
                context = this,
                resultCode = resultCode,
                data = data,
                operationId = operationId,
                reason = reason
            )

            waitUntilCaptureRunningThenFinish(
                startedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        } else {
            Log.w(
                TAG,
                "화면공유 권한 거부됨. reason=$reason operationId=$operationId"
            )

            AutoCapturePauseStore.pause(
                context = this,
                durationMs = DENIED_RETRY_PAUSE_MS,
                reason = "MEDIA_PROJECTION_DENIED"
            )

            CaptureStateStore.setError("media_projection_permission_denied")
            AgentOperationStore.fail(
                operationId,
                "media_projection_permission_denied",
                retryable = true
            )

            finishAccordingToSource()
        }
    }

    private fun waitUntilCaptureRunningThenFinish(
        startedAtElapsedMs: Long
    ) {
        if (finishingCaptureTask) return

        val snapshot = CaptureStateStore.snapshot()
        val elapsedMs =
            SystemClock.elapsedRealtime() - startedAtElapsedMs

        val runningWithFrame =
            snapshot.state == CaptureStateStore.STATE_RUNNING &&
                    snapshot.encodedFrameCount > 0L &&
                    snapshot.frameAgeMs in 0..5_000L

        if (runningWithFrame) {
            Log.i(
                TAG,
                "송출 RUNNING 확인. frames=${snapshot.encodedFrameCount} " +
                        "frameAgeMs=${snapshot.frameAgeMs} operationId=$operationId"
            )
            finishCaptureTaskCleanly()
            return
        }

        if (elapsedMs >= WAIT_RUNNING_TIMEOUT_MS) {
            val message = "capture_first_frame_timeout"
            Log.w(
                TAG,
                "$message. state=${snapshot.state} frames=${snapshot.encodedFrameCount} " +
                        "frameAgeMs=${snapshot.frameAgeMs} operationId=$operationId"
            )

            CaptureStateStore.setError(message)
            AgentOperationStore.fail(
                operationId,
                message,
                retryable = true
            )
            CaptureService.stop(this)
            finishCaptureTaskCleanly()
            return
        }

        handler.postDelayed(
            { waitUntilCaptureRunningThenFinish(startedAtElapsedMs) },
            WAIT_RUNNING_INTERVAL_MS
        )
    }

    private fun failOperationOnlyAndFinish(
        message: String,
        retryable: Boolean
    ) {
        Log.w(
            TAG,
            "Capture request rejected without changing active state. " +
                    "message=$message operationId=$operationId"
        )
        AgentOperationStore.fail(operationId, message, retryable)
        finishCaptureTaskCleanly()
    }

    private fun failAndFinish(
        message: String,
        retryable: Boolean
    ) {
        Log.w(
            TAG,
            "Capture permission flow failed. message=$message reason=$reason operationId=$operationId"
        )
        CaptureStateStore.setError(message)
        AgentOperationStore.fail(operationId, message, retryable)
        finishCaptureTaskCleanly()
    }

    private fun failAndFinishAccordingToSource(
        message: String,
        retryable: Boolean
    ) {
        requested = false
        resultHandled = true
        handler.removeCallbacks(permissionTimeoutRunnable)
        CaptureStateStore.setError(message)
        AgentOperationStore.fail(operationId, message, retryable)
        finishAccordingToSource()
    }

    /**
     * WPF/와치독/부팅 복구에서 발생한 실패는 MainActivity를 전면에 표시하지 않습니다.
     * 수동으로 Agent 화면에서 시작한 요청만 오류 UI를 보여 줍니다.
     */
    private fun finishAccordingToSource() {
        if (isRemoteOrAutomaticRequest()) {
            finishCaptureTaskCleanly()
        } else {
            showAgentUiAndFinish()
        }
    }

    private fun isRemoteOrAutomaticRequest(): Boolean {
        val normalized = reason.trim().uppercase()

        return normalized.startsWith("HTTP_") ||
                normalized.startsWith("WATCHDOG") ||
                normalized.startsWith("NETWORK_") ||
                normalized.startsWith("SCREEN_") ||
                normalized.startsWith("SERVICE_") ||
                normalized.startsWith("ON_START_COMMAND") ||
                normalized.startsWith("DEVICE_IDLE_") ||
                normalized.startsWith("PAUSED_") ||
                normalized.startsWith("AUTO_") ||
                normalized.startsWith("BOOT_") ||
                normalized.startsWith("FORCE_REFRESH_")
    }

    private fun showAgentUiAndFinish() {
        if (finishingCaptureTask) return

        try {
            val agentIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("suppressAutoStartOnce", true)
            }

            startActivity(agentIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Agent UI 표시 실패", e)
        }

        finishCleanly()
    }

    private fun cancelOperationForExternalLaunch() {
        if (
            operationId.isNotBlank() &&
            AgentOperationStore.isPending(operationId)
        ) {
            AgentOperationStore.cancel(
                operationId,
                "capture_ui_deferred_by_storywing_launch"
            )
        }
    }

    /**
     * 외부 앱을 전면 실행하기 직전에 호출하는 안전 정리 경로입니다.
     * finishAndRemoveTask() 대신 plain finish()만 사용합니다.
     */
    private fun finishForExternalLaunch() {
        if (finishingCaptureTask) return

        finishingCaptureTask = true
        requested = false
        resultHandled = true

        handler.removeCallbacksAndMessages(
            null
        )

        cancelOperationForExternalLaunch()

        AgentUiState.isCapturePermissionActivityAlive =
            false

        try {
            finish()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "CapturePermissionActivity plain finish failed",
                e
            )
        }

        try {
            overridePendingTransition(
                0,
                0
            )
        } catch (_: Exception) {
        }
    }

    /**
     * WPF의 앱 종료 명령이 들어온 경우 권한창과 투명 Activity를
     * 즉시 제거하고 MainActivity를 다시 띄우지 않습니다.
     */
    private fun finishForUiDisable() {
        if (finishingCaptureTask) return

        requested = false
        resultHandled = true
        handler.removeCallbacksAndMessages(null)

        if (operationId.isNotBlank() &&
            AgentOperationStore.isPending(operationId)
        ) {
            AgentOperationStore.cancel(
                operationId,
                "ui_disabled_by_app_stop"
            )
        }

        finishCaptureTaskCleanly()
    }

    private fun finishCleanly() {
        finishingCaptureTask = true
        handler.removeCallbacksAndMessages(null)

        try {
            finish()
        } catch (_: Exception) {
        }

        try {
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
        }
    }

    private fun finishCaptureTaskCleanly() {
        if (finishingCaptureTask) return
        finishingCaptureTask = true

        handler.removeCallbacksAndMessages(null)

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

    override fun onDestroy() {
        if (currentRef?.get() === this) {
            currentRef = null
        }

        AgentUiState.isCapturePermissionActivityAlive = false
        handler.removeCallbacksAndMessages(null)

        if (
            !isChangingConfigurations &&
            !resultHandled &&
            requested &&
            AgentOperationStore.isPending(operationId)
        ) {
            AgentOperationStore.fail(
                operationId,
                "permission_activity_destroyed",
                retryable = true
            )
        }

        super.onDestroy()
    }
}