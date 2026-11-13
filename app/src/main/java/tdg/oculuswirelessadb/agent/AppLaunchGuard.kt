package tdg.oculuswirelessadb.agent

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * StoryWing Unity/OpenXR 앱의 첫 프레임 구간만 보호합니다.
 *
 * R2의 12시간 지속 세션은 제거했습니다. 일반 앱이 APP_STATE를 보내지 않아도
 * 유한 시간 뒤 자동으로 해제되며, STOP/STOP_ALL은 즉시 clear합니다.
 */
object AppLaunchGuard {

    private const val TAG = "MQ-AppLaunchGuard"

    const val DEFAULT_GUARD_MS = 20_000L
    private const val ACTIVE_TAIL_MS = 1_500L

    private val lock = Any()

    @Volatile
    private var guardedPackageName: String = ""

    @Volatile
    private var startedAtElapsedMs: Long = 0L

    @Volatile
    private var suppressUntilElapsedMs: Long = 0L

    @Volatile
    private var foregroundReady: Boolean = false

    fun begin(
        packageName: String,
        durationMs: Long = DEFAULT_GUARD_MS
    ) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        val safeDuration = durationMs.coerceIn(5_000L, 60_000L)

        synchronized(lock) {
            guardedPackageName = normalized
            startedAtElapsedMs = now
            suppressUntilElapsedMs = now + safeDuration
            foregroundReady = false
        }

        Log.i(
            TAG,
            "Finite launch guard started. package=$normalized durationMs=$safeDuration"
        )
    }

    fun markForegroundReady(
        packageName: String,
        tailMs: Long = ACTIVE_TAIL_MS
    ) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        val safeTail = tailMs.coerceIn(500L, 5_000L)

        val updated = synchronized(lock) {
            if (!guardedPackageName.equals(normalized, ignoreCase = true)) {
                false
            } else {
                foregroundReady = true
                suppressUntilElapsedMs = now + safeTail
                true
            }
        }

        if (updated) {
            Log.i(
                TAG,
                "Foreground ready; guard tail applied. package=$normalized tailMs=$safeTail"
            )
        }
    }

    fun clear() {
        val previous = synchronized(lock) {
            val value = guardedPackageName
            guardedPackageName = ""
            startedAtElapsedMs = 0L
            suppressUntilElapsedMs = 0L
            foregroundReady = false
            value
        }

        if (previous.isNotBlank()) {
            Log.i(TAG, "Finite launch guard cleared. package=$previous")
        }
    }

    fun clearFor(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return

        val matches = synchronized(lock) {
            guardedPackageName.equals(normalized, ignoreCase = true)
        }

        if (matches) clear()
    }

    fun isActive(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val active = guardedPackageName.isNotBlank() && now < suppressUntilElapsedMs

        if (!active && guardedPackageName.isNotBlank()) {
            clear()
        }

        return active
    }

    fun isActiveFor(packageName: String): Boolean {
        return isActive() && guardedPackageName.equals(
            packageName.trim(),
            ignoreCase = true
        )
    }

    fun isFirstFrameProtectionActive(): Boolean = isActive()

    fun isFirstFrameProtectionActiveFor(packageName: String): Boolean {
        return isActiveFor(packageName)
    }

    fun remainingMs(): Long {
        if (!isActive()) return 0L
        return (suppressUntilElapsedMs - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
    }

    fun sessionAgeMs(): Long {
        if (!isActive() || startedAtElapsedMs <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - startedAtElapsedMs)
            .coerceAtLeast(0L)
    }

    fun packageName(): String {
        isActive()
        return guardedPackageName
    }

    fun isForegroundReady(): Boolean = isActive() && foregroundReady

    fun toJson(): JSONObject {
        return JSONObject()
            .put("active", isActive())
            .put("packageName", guardedPackageName)
            .put("foregroundReady", foregroundReady)
            .put("firstFrameProtectionActive", isFirstFrameProtectionActive())
            .put("remainingMs", remainingMs())
            .put("sessionAgeMs", sessionAgeMs())
            .put("persistentSession", false)
    }
}