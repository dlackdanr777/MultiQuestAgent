package tdg.oculuswirelessadb.agent

import android.os.SystemClock
import org.json.JSONObject

/**
 * StoryWing 앱 실행 중 Agent의 자동 캡처 복구 Activity가 그 위로 올라오는 것을 막습니다.
 *
 * HTTP launch 요청부터 APP_STATE_ACTIVE 수신까지 자동 복구만 잠시 억제합니다.
 * 명시적인 WPF 캡처 시작/재시작 요청은 차단하지 않습니다.
 */
object AppLaunchGuard {

    private const val DEFAULT_GUARD_MS = 45_000L

    private val lock = Any()

    @Volatile
    private var guardedPackageName: String = ""

    @Volatile
    private var suppressUntilElapsedMs: Long = 0L

    fun begin(
        packageName: String,
        durationMs: Long = DEFAULT_GUARD_MS
    ) {
        if (packageName.isBlank()) return

        synchronized(lock) {
            guardedPackageName = packageName.trim()
            suppressUntilElapsedMs =
                SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(1_000L)
        }
    }

    fun clear() {
        synchronized(lock) {
            guardedPackageName = ""
            suppressUntilElapsedMs = 0L
        }
    }

    fun clearFor(packageName: String) {
        synchronized(lock) {
            if (guardedPackageName.equals(packageName, ignoreCase = true)) {
                guardedPackageName = ""
                suppressUntilElapsedMs = 0L
            }
        }
    }

    fun isActive(): Boolean {
        val active = SystemClock.elapsedRealtime() < suppressUntilElapsedMs
        if (!active && suppressUntilElapsedMs != 0L) {
            clear()
        }
        return active
    }

    fun remainingMs(): Long {
        val remaining = suppressUntilElapsedMs - SystemClock.elapsedRealtime()
        return remaining.coerceAtLeast(0L)
    }

    fun packageName(): String = guardedPackageName

    fun toJson(): JSONObject {
        return JSONObject()
            .put("active", isActive())
            .put("packageName", guardedPackageName)
            .put("remainingMs", remainingMs())
    }
}