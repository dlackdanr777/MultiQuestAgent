package tdg.oculuswirelessadb

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import tdg.oculuswirelessadb.agent.AppLaunchGuard
import java.lang.ref.WeakReference

/**
 * 화면 깨우기 보조용 투명 Activity입니다.
 *
 * StoryWing 외부 앱 실행이 시작되면 즉시 plain finish하여
 * Unity/OpenXR Activity 앞에 남지 않게 합니다.
 */
class KeepAwakeActivity : Activity() {

    companion object {
        private const val TAG =
            "KeepAwakeActivity"

        private var currentRef:
                WeakReference<KeepAwakeActivity>? =
            null

        private val mainHandler =
            Handler(
                Looper.getMainLooper()
            )

        fun finishCurrent() {
            val action = Runnable {
                currentRef
                    ?.get()
                    ?.finishImmediately()
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

        fun finishCurrentForExternalLaunchNow() {
            finishCurrent()
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

        if (AppLaunchGuard.isActive()) {
            Log.i(
                TAG,
                "KeepAwakeActivity suppressed by launch guard. " +
                        "package=${AppLaunchGuard.packageName()}"
            )

            finishImmediately()
            return
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        window.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        window.setDimAmount(
            0f
        )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O_MR1
        ) {
            try {
                setTurnScreenOn(
                    true
                )

                setShowWhenLocked(
                    true
                )
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "setTurnScreenOn/setShowWhenLocked failed",
                    e
                )
            }
        }

        setContentView(
            FrameLayout(this).apply {
                foregroundGravity =
                    Gravity.CENTER

                setBackgroundColor(
                    Color.TRANSPARENT
                )
            }
        )

        Log.i(
            TAG,
            "KeepAwakeActivity started"
        )
    }

    override fun onResume() {
        super.onResume()

        if (AppLaunchGuard.isActive()) {
            finishImmediately()
        }
    }

    private fun finishImmediately() {
        try {
            if (
                !isFinishing &&
                !isDestroyed
            ) {
                finish()

                overridePendingTransition(
                    0,
                    0
                )
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "KeepAwakeActivity finish failed",
                e
            )
        } finally {
            if (
                currentRef?.get() ===
                this
            ) {
                currentRef =
                    null
            }
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

        super.onDestroy()

        Log.i(
            TAG,
            "KeepAwakeActivity destroyed"
        )
    }
}