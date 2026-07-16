package tdg.oculuswirelessadb.capture

object CaptureStateStore {

    const val STATE_IDLE = "IDLE"
    const val STATE_STOPPED = "STOPPED"
    const val STATE_WAITING_PERMISSION = "WAITING_PERMISSION"
    const val STATE_STARTING = "STARTING"
    const val STATE_RUNNING = "RUNNING"
    const val STATE_ERROR = "ERROR"
    const val STATE_FROZEN = "FROZEN"

    // 20대 동시 운용 기본값
    private const val DEFAULT_WIDTH = 960
    private const val DEFAULT_HEIGHT = 540
    private const val DEFAULT_FPS = 10
    private const val DEFAULT_BITRATE = 700_000

    // 안전 상한값
    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 720
    private const val MAX_FPS = 15
    private const val MAX_BITRATE = 1_500_000

    // 너무 낮게 설정되는 것 방지
    private const val MIN_WIDTH = 480
    private const val MIN_HEIGHT = 270
    private const val MIN_FPS = 5
    private const val MIN_BITRATE = 300_000

    @Volatile
    private var _state: String = STATE_IDLE

    var state: String
        get() = _state
        set(value) {
            if (_state != value) {
                _state = value
                lastStateChangedAtMs = System.currentTimeMillis()
            }
        }

    @Volatile
    var lastStateChangedAtMs: Long = System.currentTimeMillis()

    @Volatile
    var rtspUrl: String = ""

    @Volatile
    var streamGeneration: Int = 0

    @Volatile
    var encodedFrameCount: Long = 0

    @Volatile
    var lastFrameAtMs: Long = 0

    @Volatile
    var rtspClientCount: Int = 0

    @Volatile
    var width: Int = DEFAULT_WIDTH

    @Volatile
    var height: Int = DEFAULT_HEIGHT

    @Volatile
    var fps: Int = DEFAULT_FPS

    @Volatile
    var bitrate: Int = DEFAULT_BITRATE

    @Volatile
    var lastErrorMessage: String = ""

    fun getStateDurationMs(): Long {
        return System.currentTimeMillis() - lastStateChangedAtMs
    }

    fun getFrameAgeMs(): Long {
        if (lastFrameAtMs <= 0L) return -1L
        return System.currentTimeMillis() - lastFrameAtMs
    }

    fun reset() {
        clearToIdle()
    }

    fun clearToIdle() {
        state = STATE_IDLE
        rtspUrl = ""
        encodedFrameCount = 0
        lastFrameAtMs = 0
        rtspClientCount = 0
        lastErrorMessage = ""
    }

    fun markWaitingPermission() {
        state = STATE_WAITING_PERMISSION
        rtspUrl = ""
        encodedFrameCount = 0
        lastFrameAtMs = 0
        rtspClientCount = 0
        lastErrorMessage = ""
    }

    fun markStopped() {
        state = STATE_STOPPED
        rtspUrl = ""
        encodedFrameCount = 0
        lastFrameAtMs = 0
        rtspClientCount = 0
        lastErrorMessage = ""
    }

    fun startNewSession() {
        streamGeneration++
        state = STATE_STARTING
        rtspUrl = ""
        encodedFrameCount = 0

        // 중요:
        // 실제 프레임이 나오기 전까지는 lastFrameAtMs를 0으로 둡니다.
        // 그래야 WPF가 "프레임이 아직 없음"과 "프레임이 오래됨"을 구분할 수 있습니다.
        lastFrameAtMs = 0

        rtspClientCount = 0
        lastErrorMessage = ""
    }

    fun setRunning(url: String) {
        state = STATE_RUNNING
        rtspUrl = url
        lastErrorMessage = ""
    }

    fun updateFrameStatus() {
        encodedFrameCount++
        lastFrameAtMs = System.currentTimeMillis()

        if (state == STATE_STARTING ||
            state == STATE_IDLE ||
            state == STATE_WAITING_PERMISSION ||
            state == STATE_FROZEN
        ) {
            state = STATE_RUNNING
        }
    }

    fun updateRtspClientCount(count: Int) {
        rtspClientCount = count.coerceAtLeast(0)
    }

    fun markFrozen() {
        state = STATE_FROZEN
    }

    fun setError(message: String = "") {
        state = STATE_ERROR
        rtspUrl = ""
        lastErrorMessage = message
    }

    fun setCaptureConfig(
        newWidth: Int,
        newHeight: Int,
        newFps: Int,
        newBitrate: Int
    ) {
        width = sanitizeEven(
            newWidth,
            min = MIN_WIDTH,
            max = MAX_WIDTH,
            fallback = DEFAULT_WIDTH
        )

        height = sanitizeEven(
            newHeight,
            min = MIN_HEIGHT,
            max = MAX_HEIGHT,
            fallback = DEFAULT_HEIGHT
        )

        fps = sanitizeInt(
            newFps,
            min = MIN_FPS,
            max = MAX_FPS,
            fallback = DEFAULT_FPS
        )

        bitrate = sanitizeInt(
            newBitrate,
            min = MIN_BITRATE,
            max = MAX_BITRATE,
            fallback = DEFAULT_BITRATE
        )
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