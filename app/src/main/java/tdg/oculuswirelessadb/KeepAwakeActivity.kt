package tdg.oculuswirelessadb

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.setPadding
import java.lang.ref.WeakReference

class KeepAwakeActivity : Activity() {

    companion object {
        private const val TAG = "KeepAwakeActivity"

        private var currentRef: WeakReference<KeepAwakeActivity>? = null

        fun finishCurrent() {
            try {
                currentRef?.get()?.finish()
            } catch (_: Exception) {
            } finally {
                currentRef = null
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
        window.setDimAmount(0.0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                setTurnScreenOn(true)
                setShowWhenLocked(true)
            } catch (e: Exception) {
                Log.w(TAG, "setTurnScreenOn/setShowWhenLocked failed", e)
            }
        }

        val root = FrameLayout(this).apply {
            setPadding(0)
            foregroundGravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        setContentView(root)

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