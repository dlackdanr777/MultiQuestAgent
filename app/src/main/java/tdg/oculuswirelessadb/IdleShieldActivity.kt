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
import tdg.oculuswirelessadb.agent.AppLaunchGuard
import java.lang.ref.WeakReference

/**
 * StoryWing 앱 종료 직후 Quest Library/Home이 바로 보이는 것을 막기 위한
 * 무조작 검은 화면입니다.
 *
 * 외부 StoryWing 앱 실행이 시작되면 AppLaunchGuard를 기준으로 즉시 종료합니다.
 * 따라서 Shield가 새 Unity/OpenXR 앱 위에서 입력 포커스를 가로채지 않습니다.
 */
class IdleShieldActivity : Activity() {

    companion object {
        private const val TAG =
            "MQ-IdleShield"

        private const val EXTRA_REASON =
            "reason"

        private var currentRef:
                WeakReference<IdleShieldActivity>? =
            null

        private val mainHandler =
            Handler(
                Looper.getMainLooper()
            )

        fun createIntent(
            context: Context,
            reason: String
        ): Intent {
            return Intent(
                context,
                IdleShieldActivity::class.java
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )

                putExtra(
                    EXTRA_REASON,
                    reason
                )
            }
        }

        fun finishCurrent() {
            runOnMain {
                val activity =
                    currentRef?.get()

                activity?.finishForStopCleanup(
                    removeTask = true
                )
            }
        }

        /**
         * AgentCommandHandler가 대상 앱을 startActivity하기 직전에,
         * 같은 Main Looper 작업 안에서 호출합니다.
         */
        fun finishCurrentForExternalLaunchNow() {
            val action = Runnable {
                val activity =
                    currentRef?.get()

                activity?.finishForStopCleanup(
                    removeTask = false
                )
            }

            if (
                Looper.myLooper() ==
                Looper.getMainLooper()
            ) {
                action.run()
            } else {
                mainHandler.post(action)
            }
        }

        private fun runOnMain(
            action: () -> Unit
        ) {
            if (
                Looper.myLooper() ==
                Looper.getMainLooper()
            ) {
                action()
            } else {
                mainHandler.post(action)
            }
        }
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        currentRef =
            WeakReference(this)

        AgentUiState.isIdleShieldVisible =
            true

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "Shield creation suppressed by launch guard. " +
                        "package=${AppLaunchGuard.packageName()}"
            )

            finishForStopCleanup(
                removeTask = false
            )

            return
        }

        window.setBackgroundDrawableResource(
            android.R.color.black
        )

        window.setDimAmount(
            0f
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O_MR1
        ) {
            try {
                setShowWhenLocked(
                    true
                )

                setTurnScreenOn(
                    true
                )
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
                setBackgroundColor(
                    Color.BLACK
                )

                isClickable =
                    true

                isFocusable =
                    true

                isFocusableInTouchMode =
                    true

                setOnClickListener {
                    // 종료 대기 화면에서는 입력을 처리하지 않습니다.
                }

                requestFocus()
            }
        )

        val reason =
            intent
                ?.getStringExtra(
                    EXTRA_REASON
                )
                .orEmpty()

        Log.i(
            TAG,
            "Idle shield shown. " +
                    "reason=$reason"
        )

        try {
            overridePendingTransition(
                0,
                0
            )
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(
        intent: Intent?
    ) {
        super.onNewIntent(
            intent
        )

        setIntent(
            intent
        )

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "Reused Shield closed by launch guard. " +
                        "package=${AppLaunchGuard.packageName()}"
            )

            finishForStopCleanup(
                removeTask = false
            )

            return
        }

        val reason =
            intent
                ?.getStringExtra(
                    EXTRA_REASON
                )
                .orEmpty()

        Log.i(
            TAG,
            "Idle shield reused. " +
                    "reason=$reason"
        )
    }

    override fun onResume() {
        super.onResume()

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "Shield resumed during StoryWing launch; closing. " +
                        "package=${AppLaunchGuard.packageName()}"
            )

            finishForStopCleanup(
                removeTask = false
            )
        }
    }

    override fun onPause() {
        /*
         * StoryWing 앱이 전면으로 올라가 Shield가 pause되는 순간
         * background task에 남지 않도록 plain finish합니다.
         */
        if (AppLaunchGuard.isActive()) {
            finishForStopCleanup(
                removeTask = false
            )
        }

        super.onPause()
    }

    @Deprecated(
        "Deprecated in Java"
    )
    override fun onBackPressed() {
        // 종료 화면에서 뒤로가기로 Library/Home이 노출되지 않게 합니다.
    }

    private fun finishForStopCleanup(
        removeTask: Boolean
    ) {
        try {
            if (
                !isFinishing &&
                !isDestroyed
            ) {
                if (
                    removeTask &&
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.LOLLIPOP
                ) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }

                overridePendingTransition(
                    0,
                    0
                )
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Shield finish failed. " +
                        "removeTask=$removeTask",
                e
            )

            try {
                finish()
            } catch (_: Exception) {
            }
        } finally {
            if (
                currentRef?.get() ===
                this
            ) {
                currentRef =
                    null
            }

            AgentUiState.isIdleShieldVisible =
                false
        }
    }

    override fun onDestroy() {
        if (
            currentRef?.get() ===
            this
        ) {
            currentRef =
                null
        }

        AgentUiState.isIdleShieldVisible =
            false

        super.onDestroy()
    }
}