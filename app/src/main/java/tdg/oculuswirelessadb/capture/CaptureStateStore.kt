package tdg.oculuswirelessadb.capture

import android.os.SystemClock

/**
 * 캡처 상태의 단일 진실 공급원입니다.
 *
 * 여러 @Volatile 필드를 각각 읽는 방식은 서로 다른 시점의 값이 섞일 수 있으므로,
 * 모든 복합 상태 변경과 스냅샷 읽기를 하나의 lock으로 보호합니다.
 */
object CaptureStateStore {

    const val STATE_IDLE = "IDLE"
    const val STATE_STOPPED = "STOPPED"
    const val STATE_WAITING_PERMISSION = "WAITING_PERMISSION"
    const val STATE_STARTING = "STARTING"
    const val STATE_RUNNING = "RUNNING"
    const val STATE_ERROR = "ERROR"
    const val STATE_FROZEN = "FROZEN"

    private const val DEFAULT_WIDTH = 960
    private const val DEFAULT_HEIGHT = 540
    private const val DEFAULT_FPS = 10
    private const val DEFAULT_BITRATE = 700_000

    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 720
    private const val MAX_FPS = 15
    private const val MAX_BITRATE = 1_500_000

    private const val MIN_WIDTH = 480
    private const val MIN_HEIGHT = 270
    private const val MIN_FPS = 5
    private const val MIN_BITRATE = 300_000

    data class Snapshot(
        val state: String,
        val stateChangedAtMs: Long,
        val stateDurationMs: Long,
        val rtspUrl: String,
        val streamGeneration: Int,
        val encodedFrameCount: Long,
        val lastFrameAtMs: Long,
        val frameAgeMs: Long,
        val rtspClientCount: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val lastErrorMessage: String,
        val activeOperationId: String
    )

    private val lock = Any()

    private var stateValue: String = STATE_IDLE
    private var stateChangedAtEpochMsValue: Long = System.currentTimeMillis()
    private var stateChangedAtElapsedMsValue: Long = SystemClock.elapsedRealtime()

    private var rtspUrlValue: String = ""
    private var streamGenerationValue: Int = 0
    private var encodedFrameCountValue: Long = 0L
    private var lastFrameAtEpochMsValue: Long = 0L
    private var lastFrameAtElapsedMsValue: Long = 0L
    private var rtspClientCountValue: Int = 0

    private var widthValue: Int = DEFAULT_WIDTH
    private var heightValue: Int = DEFAULT_HEIGHT
    private var fpsValue: Int = DEFAULT_FPS
    private var bitrateValue: Int = DEFAULT_BITRATE

    private var lastErrorMessageValue: String = ""
    private var activeOperationIdValue: String = ""

    var state: String
        get() = synchronized(lock) { stateValue }
        set(value) = synchronized(lock) {
            setStateLocked(value)
        }

    val lastStateChangedAtMs: Long
        get() = synchronized(lock) { stateChangedAtEpochMsValue }

    var rtspUrl: String
        get() = synchronized(lock) { rtspUrlValue }
        set(value) = synchronized(lock) {
            rtspUrlValue = value.trim()
        }

    var streamGeneration: Int
        get() = synchronized(lock) { streamGenerationValue }
        set(value) = synchronized(lock) {
            streamGenerationValue = value.coerceAtLeast(0)
        }

    var encodedFrameCount: Long
        get() = synchronized(lock) { encodedFrameCountValue }
        set(value) = synchronized(lock) {
            encodedFrameCountValue = value.coerceAtLeast(0L)
        }

    var lastFrameAtMs: Long
        get() = synchronized(lock) { lastFrameAtEpochMsValue }
        set(value) = synchronized(lock) {
            lastFrameAtEpochMsValue = value.coerceAtLeast(0L)
            lastFrameAtElapsedMsValue = if (value > 0L) {
                SystemClock.elapsedRealtime()
            } else {
                0L
            }
        }

    var rtspClientCount: Int
        get() = synchronized(lock) { rtspClientCountValue }
        set(value) = synchronized(lock) {
            rtspClientCountValue = value.coerceAtLeast(0)
        }

    var width: Int
        get() = synchronized(lock) { widthValue }
        set(value) = synchronized(lock) {
            widthValue = sanitizeEven(value, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH)
        }

    var height: Int
        get() = synchronized(lock) { heightValue }
        set(value) = synchronized(lock) {
            heightValue = sanitizeEven(value, MIN_HEIGHT, MAX_HEIGHT, DEFAULT_HEIGHT)
        }

    var fps: Int
        get() = synchronized(lock) { fpsValue }
        set(value) = synchronized(lock) {
            fpsValue = sanitizeInt(value, MIN_FPS, MAX_FPS, DEFAULT_FPS)
        }

    var bitrate: Int
        get() = synchronized(lock) { bitrateValue }
        set(value) = synchronized(lock) {
            bitrateValue = sanitizeInt(value, MIN_BITRATE, MAX_BITRATE, DEFAULT_BITRATE)
        }

    var lastErrorMessage: String
        get() = synchronized(lock) { lastErrorMessageValue }
        set(value) = synchronized(lock) {
            lastErrorMessageValue = value.trim()
        }

    var activeOperationId: String
        get() = synchronized(lock) { activeOperationIdValue }
        set(value) = synchronized(lock) {
            activeOperationIdValue = value.trim()
        }

    fun getStateDurationMs(): Long = synchronized(lock) {
        elapsedSince(stateChangedAtElapsedMsValue)
    }

    fun getFrameAgeMs(): Long = synchronized(lock) {
        if (lastFrameAtElapsedMsValue <= 0L) {
            -1L
        } else {
            elapsedSince(lastFrameAtElapsedMsValue)
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            state = stateValue,
            stateChangedAtMs = stateChangedAtEpochMsValue,
            stateDurationMs = elapsedSince(stateChangedAtElapsedMsValue),
            rtspUrl = rtspUrlValue,
            streamGeneration = streamGenerationValue,
            encodedFrameCount = encodedFrameCountValue,
            lastFrameAtMs = lastFrameAtEpochMsValue,
            frameAgeMs = if (lastFrameAtElapsedMsValue <= 0L) {
                -1L
            } else {
                elapsedSince(lastFrameAtElapsedMsValue)
            },
            rtspClientCount = rtspClientCountValue,
            width = widthValue,
            height = heightValue,
            fps = fpsValue,
            bitrate = bitrateValue,
            lastErrorMessage = lastErrorMessageValue,
            activeOperationId = activeOperationIdValue
        )
    }

    fun reset() {
        clearToIdle()
    }

    fun clearToIdle(clearOperation: Boolean = true) = synchronized(lock) {
        setStateLocked(STATE_IDLE)
        clearSessionTelemetryLocked()
        lastErrorMessageValue = ""
        if (clearOperation) {
            activeOperationIdValue = ""
        }
    }

    fun markWaitingPermission(operationId: String = "") = synchronized(lock) {
        setStateLocked(STATE_WAITING_PERMISSION)
        clearSessionTelemetryLocked()
        lastErrorMessageValue = ""
        activeOperationIdValue = operationId.trim()
    }

    fun markStopped(clearOperation: Boolean = true) = synchronized(lock) {
        setStateLocked(STATE_STOPPED)
        clearSessionTelemetryLocked()
        lastErrorMessageValue = ""
        if (clearOperation) {
            activeOperationIdValue = ""
        }
    }

    fun startNewSession(operationId: String = ""): Int = synchronized(lock) {
        streamGenerationValue += 1
        setStateLocked(STATE_STARTING)
        clearSessionTelemetryLocked()
        lastErrorMessageValue = ""
        activeOperationIdValue = operationId.trim()
        streamGenerationValue
    }

    fun setRunning(url: String) = synchronized(lock) {
        setStateLocked(STATE_RUNNING)
        rtspUrlValue = url.trim()
        lastErrorMessageValue = ""
    }

    /**
     * 프레임 카운터와 시각을 같은 임계구역에서 갱신합니다.
     * 반환값은 갱신 후 프레임 번호입니다.
     */
    fun updateFrameStatus(): Long = synchronized(lock) {
        encodedFrameCountValue += 1L
        lastFrameAtEpochMsValue = System.currentTimeMillis()
        lastFrameAtElapsedMsValue = SystemClock.elapsedRealtime()

        if (
            stateValue == STATE_STARTING ||
            stateValue == STATE_IDLE ||
            stateValue == STATE_WAITING_PERMISSION ||
            stateValue == STATE_FROZEN
        ) {
            setStateLocked(STATE_RUNNING)
        }

        encodedFrameCountValue
    }

    fun updateRtspClientCount(count: Int) = synchronized(lock) {
        rtspClientCountValue = count.coerceAtLeast(0)
    }

    fun markFrozen(message: String = "") = synchronized(lock) {
        setStateLocked(STATE_FROZEN)
        if (message.isNotBlank()) {
            lastErrorMessageValue = message.trim()
        }
    }

    fun setError(
        message: String = "",
        clearOperation: Boolean = false
    ) = synchronized(lock) {
        setStateLocked(STATE_ERROR)
        rtspUrlValue = ""
        rtspClientCountValue = 0
        lastErrorMessageValue = message.trim()
        if (clearOperation) {
            activeOperationIdValue = ""
        }
    }

    fun completeOperation(operationId: String) = synchronized(lock) {
        if (operationId.isNotBlank() && activeOperationIdValue == operationId) {
            activeOperationIdValue = ""
        }
    }

    fun setCaptureConfig(
        newWidth: Int,
        newHeight: Int,
        newFps: Int,
        newBitrate: Int
    ) = synchronized(lock) {
        widthValue = sanitizeEven(newWidth, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH)
        heightValue = sanitizeEven(newHeight, MIN_HEIGHT, MAX_HEIGHT, DEFAULT_HEIGHT)
        fpsValue = sanitizeInt(newFps, MIN_FPS, MAX_FPS, DEFAULT_FPS)
        bitrateValue = sanitizeInt(newBitrate, MIN_BITRATE, MAX_BITRATE, DEFAULT_BITRATE)
    }

    fun applyMultiDeviceProfile() {
        setCaptureConfig(
            newWidth = DEFAULT_WIDTH,
            newHeight = DEFAULT_HEIGHT,
            newFps = DEFAULT_FPS,
            newBitrate = DEFAULT_BITRATE
        )
    }

    fun applyHighQualityProfile() {
        setCaptureConfig(
            newWidth = 1280,
            newHeight = 720,
            newFps = 15,
            newBitrate = 1_500_000
        )
    }

    private fun setStateLocked(value: String) {
        val normalized = value.trim().ifBlank { STATE_IDLE }
        if (stateValue != normalized) {
            stateValue = normalized
            stateChangedAtEpochMsValue = System.currentTimeMillis()
            stateChangedAtElapsedMsValue = SystemClock.elapsedRealtime()
        }
    }

    private fun clearSessionTelemetryLocked() {
        rtspUrlValue = ""
        encodedFrameCountValue = 0L
        lastFrameAtEpochMsValue = 0L
        lastFrameAtElapsedMsValue = 0L
        rtspClientCountValue = 0
    }

    private fun elapsedSince(startElapsedMs: Long): Long {
        if (startElapsedMs <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - startElapsedMs).coerceAtLeast(0L)
    }

    private fun sanitizeInt(
        value: Int,
        min: Int,
        max: Int,
        fallback: Int
    ): Int {
        if (value <= 0) return fallback
        return value.coerceIn(min, max)
    }

    private fun sanitizeEven(
        value: Int,
        min: Int,
        max: Int,
        fallback: Int
    ): Int {
        val sanitized = sanitizeInt(value, min, max, fallback)
        return if (sanitized % 2 == 0) sanitized else sanitized - 1
    }
}