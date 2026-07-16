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
import java.lang.ref.WeakReference

/**
 * 화면 깨우기 보조용 투명 Activity입니다.
 *
 * 종료 명령에서는 finishCurrent()로 즉시 제거되며,
 * 다음 StoryWing 앱 위에 남지 않도록 합니다.
 */
class KeepAwakeActivity : Activity() {

    companion object {
        private const val TAG = "KeepAwakeActivity"

        private var currentRef: WeakReference<KeepAwakeActivity>? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun finishCurrent() {
            val activity = currentRef?.get()

            mainHandler.post {
                try {
                    if (activity != null &&
                        !activity.isFinishing &&
                        !activity.isDestroyed
                    ) {
                        activity.finish()
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setDimAmount(0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                setTurnScreenOn(true)
                setShowWhenLocked(true)
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
                foregroundGravity = Gravity.CENTER
                setBackgroundColor(Color.TRANSPARENT)
            }
        )

        Log.i(TAG, "KeepAwakeActivity started")
    }

    override fun onDestroy() {
        if (currentRef?.get() === this) {
            currentRef = null
        }

        super.onDestroy()
        Log.i(TAG, "KeepAwakeActivity destroyed")
    }
}