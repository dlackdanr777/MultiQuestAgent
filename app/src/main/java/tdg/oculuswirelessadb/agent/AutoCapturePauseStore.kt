package tdg.oculuswirelessadb.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject

object AutoCapturePauseStore {

    private const val TAG = "MQ-AutoPause"
    private const val PREFS = "multiquest_auto_capture_pause"

    private const val KEY_PAUSE_UNTIL = "pauseUntilMs"
    private const val KEY_REASON = "reason"

    fun pause(
        context: Context,
        durationMs: Long,
        reason: String
    ) {
        if (durationMs <= 0L) {
            resume(context)
            return
        }

        val now = System.currentTimeMillis()
        val until =
            if (now > Long.MAX_VALUE - durationMs) {
                Long.MAX_VALUE
            } else {
                now + durationMs
            }

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PAUSE_UNTIL, until)
            .putString(KEY_REASON, reason)
            .apply()

        Log.w(
            TAG,
            "Auto capture paused. " +
                    "reason=$reason until=$until durationMs=$durationMs"
        )
    }

    /**
     * 앱 종료 후 AgentService가 권한 UI를 자동으로 다시 띄우지 않게 합니다.
     * 다음 StoryWing 실행 또는 Agent MainActivity 직접 오픈 시 resume() 됩니다.
     */
    fun pauseIndefinitely(
        context: Context,
        reason: String
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PAUSE_UNTIL, Long.MAX_VALUE)
            .putString(KEY_REASON, reason)
            .apply()

        Log.w(
            TAG,
            "Auto capture paused indefinitely. reason=$reason"
        )
    }

    fun resume(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PAUSE_UNTIL)
            .remove(KEY_REASON)
            .apply()

        Log.i(TAG, "Auto capture resumed")
    }

    fun isPaused(context: Context): Boolean {
        val until = pauseUntilMs(context)
        return until == Long.MAX_VALUE ||
                until > System.currentTimeMillis()
    }

    fun isPausedIndefinitely(context: Context): Boolean {
        return pauseUntilMs(context) == Long.MAX_VALUE
    }

    fun pauseUntilMs(context: Context): Long {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PAUSE_UNTIL, 0L)
    }

    fun reason(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REASON, "")
            .orEmpty()
    }

    fun remainingMs(context: Context): Long {
        val until = pauseUntilMs(context)

        if (until == Long.MAX_VALUE) {
            return Long.MAX_VALUE
        }

        return (
                until -
                        System.currentTimeMillis()
                ).coerceAtLeast(0L)
    }

    fun toJson(context: Context): JSONObject {
        val until = pauseUntilMs(context)

        return JSONObject()
            .put("paused", isPaused(context))
            .put("indefinite", until == Long.MAX_VALUE)
            .put("reason", reason(context))
            .put("pauseUntilMs", until)
            .put("remainingMs", remainingMs(context))
    }
}