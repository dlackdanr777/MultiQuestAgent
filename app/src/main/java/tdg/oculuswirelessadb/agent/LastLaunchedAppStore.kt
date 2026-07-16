package tdg.oculuswirelessadb.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

object LastLaunchedAppStore {

    private const val TAG = "MQ-LastApp"
    private const val PREFS = "multiquest_last_launched_app"

    private const val KEY_PACKAGE = "packageName"
    private const val KEY_ACTIVITY = "activityName"
    private const val KEY_EXTRAS_JSON = "extrasJson"
    private const val KEY_UPDATED_AT = "updatedAt"

    private const val KEY_APP_OPEN = "appOpen"
    private const val KEY_RESTORE_ALLOWED = "restoreAllowed"
    private const val KEY_LAST_ACTIVE_AT = "lastActiveAt"
    private const val KEY_LAST_CLOSED_AT = "lastClosedAt"
    private const val KEY_SLEEP_SNAPSHOT_AT = "sleepSnapshotAt"

    data class LaunchTarget(
        val packageName: String,
        val activityName: String?,
        val extrasJson: String?,
        val updatedAt: Long,
        val appOpen: Boolean,
        val restoreAllowed: Boolean,
        val lastActiveAt: Long,
        val lastClosedAt: Long,
        val sleepSnapshotAt: Long
    )

    fun save(
        context: Context,
        packageName: String,
        activityName: String? = null,
        extrasJson: String? = null
    ) {
        if (packageName.isBlank()) return

        val now = System.currentTimeMillis()

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE, packageName.trim())
            .putString(KEY_ACTIVITY, activityName ?: "")
            .putString(KEY_EXTRAS_JSON, extrasJson ?: "")
            .putLong(KEY_UPDATED_AT, now)
            .putBoolean(KEY_APP_OPEN, true)
            .putBoolean(KEY_RESTORE_ALLOWED, true)
            .putLong(KEY_LAST_ACTIVE_AT, now)
            .putLong(KEY_LAST_CLOSED_AT, 0L)
            .putLong(KEY_SLEEP_SNAPSHOT_AT, 0L)
            .apply()

        Log.i(
            TAG,
            "Last launched app saved/open. package=$packageName activity=$activityName extras=$extrasJson"
        )
    }

    fun get(context: Context): LaunchTarget? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val packageName = prefs.getString(KEY_PACKAGE, "")?.trim().orEmpty()
        if (packageName.isBlank()) return null

        val activityName = prefs.getString(KEY_ACTIVITY, "")?.trim().orEmpty()
        val extrasJson = prefs.getString(KEY_EXTRAS_JSON, "")?.trim().orEmpty()
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)

        return LaunchTarget(
            packageName = packageName,
            activityName = activityName.ifBlank { null },
            extrasJson = extrasJson.ifBlank { null },
            updatedAt = updatedAt,
            appOpen = prefs.getBoolean(KEY_APP_OPEN, false),
            restoreAllowed = prefs.getBoolean(KEY_RESTORE_ALLOWED, false),
            lastActiveAt = prefs.getLong(KEY_LAST_ACTIVE_AT, 0L),
            lastClosedAt = prefs.getLong(KEY_LAST_CLOSED_AT, 0L),
            sleepSnapshotAt = prefs.getLong(KEY_SLEEP_SNAPSHOT_AT, 0L)
        )
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        Log.i(TAG, "Last launched app cleared")
    }

    fun markOpen(
        context: Context,
        packageName: String,
        reason: String
    ) {
        if (packageName.isBlank()) return

        val target = get(context)

        if (target != null &&
            !target.packageName.equals(packageName, ignoreCase = true)
        ) {
            Log.i(
                TAG,
                "markOpen ignored: different package. target=${target.packageName} input=$packageName reason=$reason"
            )
            return
        }

        val now = System.currentTimeMillis()

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE, packageName.trim())
            .putBoolean(KEY_APP_OPEN, true)
            .putBoolean(KEY_RESTORE_ALLOWED, true)
            .putLong(KEY_LAST_ACTIVE_AT, now)
            .apply()

        Log.i(TAG, "App marked open. package=$packageName reason=$reason")
    }

    fun markClosed(
        context: Context,
        packageName: String,
        reason: String
    ) {
        if (packageName.isBlank()) return

        val target = get(context)

        if (target != null &&
            !target.packageName.equals(packageName, ignoreCase = true)
        ) {
            Log.i(
                TAG,
                "markClosed ignored: different package. target=${target.packageName} input=$packageName reason=$reason"
            )
            return
        }

        val now = System.currentTimeMillis()

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_APP_OPEN, false)
            .putBoolean(KEY_RESTORE_ALLOWED, false)
            .putLong(KEY_LAST_CLOSED_AT, now)
            .apply()

        Log.w(TAG, "App marked closed. package=$packageName reason=$reason")
    }

    fun prepareForSleep(
        context: Context,
        reason: String
    ) {
        val target = get(context) ?: run {
            Log.i(TAG, "prepareForSleep: no target. reason=$reason")
            return
        }

        val allowed =
            target.appOpen &&
                    target.restoreAllowed &&
                    target.lastClosedAt <= target.lastActiveAt

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTORE_ALLOWED, allowed)
            .putLong(KEY_SLEEP_SNAPSHOT_AT, System.currentTimeMillis())
            .apply()

        Log.i(
            TAG,
            "prepareForSleep. package=${target.packageName} appOpen=${target.appOpen} " +
                    "restoreAllowed=${target.restoreAllowed} lastActiveAt=${target.lastActiveAt} " +
                    "lastClosedAt=${target.lastClosedAt} finalAllowed=$allowed reason=$reason"
        )
    }

    fun launchLast(
        context: Context,
        reason: String,
        maxAgeMs: Long = 12 * 60 * 60 * 1000L
    ): Boolean {
        val target = get(context) ?: run {
            Log.w(TAG, "No last app to restore. reason=$reason")
            return false
        }

        val now = System.currentTimeMillis()
        val age = now - target.updatedAt

        if (age > maxAgeMs) {
            Log.w(
                TAG,
                "Last app is too old. age=${age}ms package=${target.packageName} reason=$reason"
            )
            return false
        }

        if (!target.appOpen) {
            Log.w(
                TAG,
                "Last app restore skipped: appOpen=false. package=${target.packageName} reason=$reason"
            )
            return false
        }

        if (!target.restoreAllowed) {
            Log.w(
                TAG,
                "Last app restore skipped: restoreAllowed=false. package=${target.packageName} reason=$reason"
            )
            return false
        }

        if (target.lastClosedAt > target.lastActiveAt) {
            Log.w(
                TAG,
                "Last app restore skipped: closed newer than active. " +
                        "package=${target.packageName} lastActiveAt=${target.lastActiveAt} " +
                        "lastClosedAt=${target.lastClosedAt} reason=$reason"
            )
            return false
        }

        return launch(context, target, reason)
    }

    fun launch(
        context: Context,
        target: LaunchTarget,
        reason: String
    ): Boolean {
        return try {
            val intent = if (!target.activityName.isNullOrBlank()) {
                Intent().apply {
                    setClassName(target.packageName, target.activityName)
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(target.packageName)
                    ?: Intent().apply {
                        setPackage(target.packageName)
                    }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

            applyExtras(intent, target.extrasJson)

            context.startActivity(intent)

            Log.i(
                TAG,
                "Last app restored. reason=$reason package=${target.packageName} activity=${target.activityName}"
            )

            true
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to restore last app. reason=$reason package=${target.packageName}",
                e
            )
            false
        }
    }

    fun toDebugJson(context: Context): JSONObject {
        val target = get(context)

        return JSONObject()
            .put("hasLastApp", target != null)
            .put("packageName", target?.packageName ?: "")
            .put("activityName", target?.activityName ?: "")
            .put("updatedAt", target?.updatedAt ?: 0L)
            .put("appOpen", target?.appOpen ?: false)
            .put("restoreAllowed", target?.restoreAllowed ?: false)
            .put("lastActiveAt", target?.lastActiveAt ?: 0L)
            .put("lastClosedAt", target?.lastClosedAt ?: 0L)
            .put("sleepSnapshotAt", target?.sleepSnapshotAt ?: 0L)
    }

    private fun applyExtras(
        intent: Intent,
        extrasJson: String?
    ) {
        if (extrasJson.isNullOrBlank()) return

        try {
            val json = JSONObject(extrasJson)
            val keys = json.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.opt(key)

                when (value) {
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is Double -> {
                        val asLong = value.toLong()
                        if (value == asLong.toDouble()) {
                            intent.putExtra(key, asLong)
                        } else {
                            intent.putExtra(key, value)
                        }
                    }
                    is Boolean -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    else -> intent.putExtra(key, value?.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply extrasJson=$extrasJson", e)
        }
    }
}