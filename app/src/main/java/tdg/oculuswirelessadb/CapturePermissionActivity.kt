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
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tdg.oculuswirelessadb.agent.AgentService
import tdg.oculuswirelessadb.agent.KeepAliveController
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore

class CapturePermissionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CapturePermission"
        private const val REQUEST_CAPTURE = 1000

        private const val EXTRA_REASON = "captureReason"

        private const val REQUEST_DELAY_MS = 200L
        private const val WAIT_RUNNING_TIMEOUT_MS = 10_000L
        private const val WAIT_RUNNING_INTERVAL_MS = 250L

        fun createIntent(context: Context, reason: String): Intent {
            return Intent(context, CapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

                putExtra(EXTRA_REASON, reason)
            }
        }
    }

    private lateinit var projectionManager: MediaProjectionManager

    private val handler = Handler(Looper.getMainLooper())

    private var requested = false
    private var resultHandled = false
    private var finishingCaptureTask = false
    private var reason: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reason = intent
            ?.getStringExtra(EXTRA_REASON)
            .orEmpty()

        makeWindowTransparent()

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // AgentService와 KeepAlive는 화면공유 Activity가 없어져도 계속 유지됩니다.
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

        if (!isScreenInteractive()) {
            Log.i(
                TAG,
                "화면 꺼짐 상태이므로 권한 요청을 실행하지 않습니다. reason=$reason"
            )

            finishCaptureTaskCleanly()
            return
        }

        handler.postDelayed({
            requestCapturePermissionOnce()
        }, REQUEST_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        setIntent(intent)

        reason = intent
            ?.getStringExtra(EXTRA_REASON)
            .orEmpty()

        if (!requested &&
            !resultHandled &&
            !finishingCaptureTask &&
            isScreenInteractive()
        ) {
            handler.postDelayed({
                requestCapturePermissionOnce()
            }, REQUEST_DELAY_MS)
        }
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
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

        if (isScreenInteractive() &&
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
        if (requested || resultHandled || finishingCaptureTask) {
            return
        }

        if (!isScreenInteractive()) {
            Log.i(
                TAG,
                "권한 요청 직전 화면 꺼짐 감지. reason=$reason"
            )

            finishCaptureTaskCleanly()
            return
        }

        val state = CaptureStateStore.state

        if (state == CaptureStateStore.STATE_RUNNING ||
            state == CaptureStateStore.STATE_STARTING ||
            state == CaptureStateStore.STATE_WAITING_PERMISSION
        ) {
            Log.i(
                TAG,
                "이미 송출 중 또는 준비 중입니다. " +
                        "저장된 앱을 다시 실행하지 않고 권한 Activity만 종료합니다. " +
                        "state=$state reason=$reason"
            )

            finishCaptureTaskCleanly()
            return
        }

        try {
            requested = true
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
                REQUEST_CAPTURE
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "화면공유 권한 요청 실패. reason=$reason",
                e
            )

            requested = false
            resultHandled = true

            CaptureStateStore.clearToIdle()

            // 권한 요청 자체가 실패한 경우에만 Agent UI를 보여줍니다.
            showAgentUiAndFinish()
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

        if (requestCode != REQUEST_CAPTURE) {
            finishCaptureTaskCleanly()
            return
        }

        if (resultHandled || finishingCaptureTask) {
            return
        }

        resultHandled = true
        requested = false

        if (resultCode == RESULT_OK && data != null) {
            Log.i(
                TAG,
                "화면공유 권한 획득 성공 - CaptureService 시작. reason=$reason"
            )

            CaptureService.start(
                context = this,
                resultCode = resultCode,
                data = data
            )

            waitUntilCaptureRunningThenFinish(
                startedAtMs = System.currentTimeMillis()
            )
        } else {
            Log.w(
                TAG,
                "화면공유 권한 거부됨. reason=$reason"
            )

            CaptureStateStore.clearToIdle()

            // 사용자가 권한을 거부한 경우는 Agent UI를 표시합니다.
            showAgentUiAndFinish()
        }
    }

    /**
     * 화면공유가 실제 RUNNING 상태가 될 때까지 잠깐 기다린 뒤,
     * 마지막 StoryWing 앱을 startActivity()로 다시 실행하지 않고
     * CapturePermissionActivity의 태스크만 제거합니다.
     *
     * 결과:
     * - 기존 StoryWing 태스크가 살아 있으면 그 앱이 다시 보입니다.
     * - 사용자가 StoryWing 앱을 닫았다면 Home이 보입니다.
     */
    private fun waitUntilCaptureRunningThenFinish(startedAtMs: Long) {
        if (finishingCaptureTask) {
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
                        "마지막 앱을 새로 실행하지 않고 권한 Activity 태스크만 제거합니다. " +
                        "frames=$frames frameAgeMs=$frameAgeMs reason=$reason"
            )

            finishCaptureTaskCleanly()
            return
        }

        if (elapsedMs >= WAIT_RUNNING_TIMEOUT_MS) {
            Log.w(
                TAG,
                "송출 RUNNING 대기 타임아웃. " +
                        "마지막 앱 자동 실행 없이 권한 Activity 태스크를 제거합니다. " +
                        "state=$state frames=$frames frameAgeMs=$frameAgeMs " +
                        "elapsedMs=$elapsedMs reason=$reason"
            )

            finishCaptureTaskCleanly()
            return
        }

        handler.postDelayed({
            waitUntilCaptureRunningThenFinish(startedAtMs)
        }, WAIT_RUNNING_INTERVAL_MS)
    }

    /**
     * 권한 거부나 권한 요청 오류일 때만 Agent 설정 UI를 표시합니다.
     *
     * 정상적인 절전 복귀 성공 경로에서는 호출하지 않습니다.
     */
    private fun showAgentUiAndFinish() {
        if (finishingCaptureTask) {
            return
        }

        try {
            val agentIntent = Intent(
                this,
                MainActivity::class.java
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

                // MainActivity가 열리자마자 다시 권한 요청을 반복하지 않게 합니다.
                putExtra("suppressAutoStartOnce", true)
            }

            startActivity(agentIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Agent UI 표시 실패", e)
        }

        finishCleanly()
    }

    /**
     * 현재 Activity만 일반 종료합니다.
     *
     * MainActivity를 새로 띄운 뒤 호출하는 오류/거부 경로에서 사용합니다.
     */
    private fun finishCleanly() {
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
     * 정상 화면공유 복구 경로에서 사용합니다.
     *
     * Agent Activity 태스크를 제거하더라도 AgentService는 종료되지 않습니다.
     * ForegroundService, HTTP 서버, 와치독은 계속 실행됩니다.
     *
     * 태스크 제거 후 Quest가 기존 태스크를 선택합니다.
     * - 기존 StoryWing 앱이 열려 있으면 해당 앱
     * - StoryWing 앱이 닫혀 있으면 Quest Home
     */
    private fun finishCaptureTaskCleanly() {
        if (finishingCaptureTask) {
            return
        }

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
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}