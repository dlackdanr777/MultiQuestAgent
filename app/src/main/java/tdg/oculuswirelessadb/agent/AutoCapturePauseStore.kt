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
        val until = System.currentTimeMillis() + durationMs

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PAUSE_UNTIL, until)
            .putString(KEY_REASON, reason)
            .apply()

        Log.w(
            TAG,
            "Auto capture paused. reason=$reason until=$until durationMs=$durationMs"
        )
    }

    fun resume(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PAUSE_UNTIL)
            .remove(KEY_REASON)
            .apply()

        Log.i(TAG, "Auto capture resumed")
    }

    fun isPaused(context: Context): Boolean {
        return pauseUntilMs(context) > System.currentTimeMillis()
    }

    fun pauseUntilMs(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PAUSE_UNTIL, 0L)
    }

    fun reason(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REASON, "") ?: ""
    }

    fun remainingMs(context: Context): Long {
        val remaining = pauseUntilMs(context) - System.currentTimeMillis()
        return if (remaining > 0L) remaining else 0L
    }

    fun toJson(context: Context): JSONObject {
        return JSONObject()
            .put("paused", isPaused(context))
            .put("reason", reason(context))
            .put("pauseUntilMs", pauseUntilMs(context))
            .put("remainingMs", remainingMs(context))
    }
}