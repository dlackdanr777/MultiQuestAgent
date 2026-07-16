package tdg.oculuswirelessadb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import tdg.oculuswirelessadb.agent.AgentUiState
import java.lang.ref.WeakReference

/**
 * StoryWing 앱 종료 직후 Quest Library/Home이 바로 보이는 것을 막기 위한
 * 무조작 검은 화면입니다.
 *
 * Android는 전면 Activity가 하나도 없으면 시스템 Home/Library를 표시합니다.
 * 따라서 "아무 UI도 보이지 않게" 하려면 실제로는 조작 요소가 없는 검은
 * Activity 하나가 전면에 있어야 합니다.
 */
class IdleShieldActivity : Activity() {

    companion object {
        private const val TAG = "MQ-IdleShield"
        private const val EXTRA_REASON = "reason"

        private var currentRef: WeakReference<IdleShieldActivity>? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun createIntent(
            context: Context,
            reason: String
        ): Intent {
            return Intent(context, IdleShieldActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_REASON, reason)
            }
        }

        fun finishCurrent() {
            val activity = currentRef?.get()

            mainHandler.post {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentRef = WeakReference(this)
        AgentUiState.isIdleShieldVisible = true

        window.setBackgroundDrawableResource(android.R.color.black)
        window.setDimAmount(0f)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } catch (_: Exception) {
            }
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { /* 모든 터치 무시 */ }
                requestFocus()
            }
        )

        val reason = intent
            ?.getStringExtra(EXTRA_REASON)
            .orEmpty()

        Log.i(TAG, "Idle shield shown. reason=$reason")

        try {
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        val reason = intent
            ?.getStringExtra(EXTRA_REASON)
            .orEmpty()

        Log.i(TAG, "Idle shield reused. reason=$reason")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 종료 명령 후에는 뒤로가기로 Library/Home이 노출되지 않도록 무시합니다.
    }

    override fun onDestroy() {
        if (currentRef?.get() === this) {
            currentRef = null
        }

        AgentUiState.isIdleShieldVisible = false
        super.onDestroy()
    }
}