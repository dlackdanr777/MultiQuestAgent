package tdg.oculuswirelessadb.agent

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 과거 Focus Guard 호출부와의 호환을 유지하는 상태/로그 전용 객체입니다.
 *
 * 절대 하지 않는 작업:
 * - 대상 StoryWing Activity 재실행
 * - PendingIntent focus pulse
 * - REORDER_TO_FRONT
 * - Agent Activity 반복 finish
 * - PAUSED 복구 재실행
 *
 * Unity/OpenXR Activity lifecycle은 앱과 Quest runtime에 맡깁니다.
 */
object LaunchFocusStabilizer {

    private const val TAG = "MQ-LaunchState"

    private val generation = AtomicLong(0L)

    @Volatile
    private var packageName: String = ""

    fun prepareBeforeExternalLaunch(
        context: Context,
        packageName: String,
        activityName: String
    ) {
        record(packageName, "prepare", activityName)
    }

    fun arm(
        context: Context,
        packageName: String,
        activityName: String
    ) {
        record(packageName, "armed", activityName)
    }

    fun onAppActive(
        context: Context,
        packageName: String
    ) {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isBlank()) return

        AppLaunchGuard.markForegroundReady(normalizedPackage)

        Log.i(
            TAG,
            "APP_STATE_ACTIVE observed. " +
                    "package=$normalizedPackage activityMutation=false"
        )
    }

    fun onAppPausedDuringLaunch(
        context: Context,
        packageName: String
    ) {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isBlank()) return

        Log.w(
            TAG,
            "APP_STATE_PAUSED observed. " +
                    "package=$normalizedPackage activityMutation=false"
        )
    }

    fun cancelForPackage(
        packageName: String,
        reason: String
    ) {
        val normalizedPackage = packageName.trim()
        if (!this.packageName.equals(normalizedPackage, ignoreCase = true)) return
        cancel(reason)
    }

    fun cancel(reason: String) {
        val token = generation.incrementAndGet()
        packageName = ""

        Log.i(
            TAG,
            "Launch state cancelled. reason=$reason token=$token"
        )
    }

    private fun record(
        packageName: String,
        phase: String,
        activityName: String
    ) {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isBlank()) return

        val token = generation.incrementAndGet()
        this.packageName = normalizedPackage

        Log.i(
            TAG,
            "Launch state recorded. " +
                    "phase=$phase package=$normalizedPackage " +
                    "activity=${activityName.trim()} " +
                    "activityMutation=false token=$token"
        )
    }
}