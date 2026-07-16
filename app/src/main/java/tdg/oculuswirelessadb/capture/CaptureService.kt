package tdg.oculuswirelessadb.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tdg.oculuswirelessadb.agent.AgentOperationStore
import tdg.oculuswirelessadb.agent.AgentStatus
import tdg.oculuswirelessadb.rtsp.RtspH264TcpServer
import java.util.concurrent.Executors
import kotlin.math.max

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "tdg.oculuswirelessadb.capture.START"
        const val ACTION_STOP = "tdg.oculuswirelessadb.capture.STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"
        const val EXTRA_OPERATION_ID = "OPERATION_ID"
        const val EXTRA_START_REASON = "START_REASON"

        private const val TAG = "MQ-Capture"

        private const val NOTIFICATION_ID = 24002
        private const val CHANNEL_ID = "multiquest_capture"

        private const val RTSP_PORT = 8554

        private const val MAX_MULTI_WIDTH = 960
        private const val MAX_MULTI_HEIGHT = 540
        private const val MAX_MULTI_FPS = 15
        private const val MAX_MULTI_BITRATE = 800_000

        private const val PERIODIC_KEYFRAME_INTERVAL_MS = 3_000L
        private const val PERIODIC_KEYFRAME_JITTER_RANGE_MS = 1_500L
        private const val CLIENT_CONNECT_KEYFRAME_JITTER_RANGE_MS = 700L

        private const val MIN_KEYFRAME_REQUEST_INTERVAL_MS = 700L

        private const val I_FRAME_INTERVAL_SECONDS = 3

        // 권한 Activity / MainActivity가 뒤로 빠진 뒤 VirtualDisplay 생성
        private const val VIRTUAL_DISPLAY_START_DELAY_MS = 1_200L

        @Volatile
        var isServiceAlive: Boolean = false
            private set

        @Volatile
        var isCleanupInProgress: Boolean = false
            private set

        /** 기존 호출부 호환용입니다. 실제 송출 여부는 CaptureStateStore로 판단하십시오. */
        val isRunning: Boolean
            get() = isServiceAlive

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            operationId: String = "",
            reason: String = ""
        ) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_OPERATION_ID, operationId)
                putExtra(EXTRA_START_REASON, reason)
            }

            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }

            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, CaptureService::class.java))
            }
        }

        suspend fun awaitFullyStopped(timeoutMs: Long = 8_000L): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)

            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isServiceAlive && !isCleanupInProgress) {
                    return true
                }
                delay(100L)
            }

            return !isServiceAlive && !isCleanupInProgress
        }
    }

    private data class EncoderConfig(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int
    )

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var inputSurface: Surface? = null

    @Volatile
    private var rtspServer: RtspH264TcpServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mq-capture-ops").apply {
            isDaemon = true
        }
    }

    private val captureDispatcher = captureExecutor.asCoroutineDispatcher()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isStopping = false

    @Volatile
    private var startScheduled = false

    @Volatile
    private var stopSelfWhenCleanupDone = false

    @Volatile
    private var lifecycleGeneration = 0L

    @Volatile
    private var activeOperationId: String = ""

    @Volatile
    private var activeStartReason: String = ""

    private var pendingStartRunnable: Runnable? = null

    private val rtspClientLock = Any()

    @Volatile
    private var activeRtspClientCount = 0

    @Volatile
    private var lastKeyFrameRequestAtMs = 0L

    private var keyFrameRequestJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        isCleanupInProgress = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            cancelPendingStart()

            stopCaptureAsync(
                finalState = CaptureStateStore.STATE_STOPPED,
                stopProjection = true,
                stopSelfAfter = true,
                reason = "ACTION_STOP"
            )

            return START_NOT_STICKY
        }

        if (intent.action == ACTION_START) {
            val operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty()
            val startReason = intent.getStringExtra(EXTRA_START_REASON).orEmpty()
            val state = CaptureStateStore.state

            if (
                !isStopping &&
                (
                        state == CaptureStateStore.STATE_STARTING ||
                                state == CaptureStateStore.STATE_RUNNING ||
                                startScheduled
                        )
            ) {
                Log.w(
                    TAG,
                    "ACTION_START ignored: already starting/running. " +
                            "state=$state, startScheduled=$startScheduled"
                )

                if (operationId.isNotBlank()) {
                    if (state == CaptureStateStore.STATE_RUNNING) {
                        AgentOperationStore.complete(
                            operationId,
                            "capture_already_running"
                        )
                    } else {
                        AgentOperationStore.markStarting(
                            operationId,
                            "capture_already_starting"
                        )
                    }
                }

                return START_NOT_STICKY
            }

            val startGeneration = bumpLifecycleGeneration()

            activeOperationId = operationId
            activeStartReason = startReason
            isServiceAlive = true
            isCleanupInProgress = false
            isStopping = false
            lastKeyFrameRequestAtMs = 0L

            startAsForeground("화면 송출 준비 중...")

            CaptureStateStore.startNewSession(operationId)
            if (operationId.isNotBlank()) {
                AgentOperationStore.markStarting(
                    operationId,
                    "CaptureService ACTION_START"
                )
            }

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                scheduleStartCapture(
                    resultCode = resultCode,
                    data = data,
                    generation = startGeneration
                )
            } else {
                val message = "invalid_media_projection_result"
                Log.w(TAG, "$message. resultCode=$resultCode, data=$data")
                CaptureStateStore.setError(message)
                if (operationId.isNotBlank()) {
                    AgentOperationStore.fail(
                        operationId,
                        message,
                        retryable = true
                    )
                }
                stopForegroundCompat()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    @Synchronized
    private fun bumpLifecycleGeneration(): Long {
        lifecycleGeneration += 1L
        return lifecycleGeneration
    }

    private fun scheduleStartCapture(
        resultCode: Int,
        data: Intent,
        generation: Long
    ) {
        cancelPendingStart()

        val runnable = Runnable {
            startScheduled = false

            serviceScope.launch(captureDispatcher) {
                if (generation != lifecycleGeneration) {
                    Log.i(
                        TAG,
                        "startCapture skipped: stale generation. " +
                                "generation=$generation current=$lifecycleGeneration"
                    )
                    return@launch
                }

                startCaptureInternal(
                    resultCode = resultCode,
                    data = data,
                    generation = generation
                )
            }
        }

        pendingStartRunnable = runnable
        startScheduled = true

        mainHandler.postDelayed(
            runnable,
            VIRTUAL_DISPLAY_START_DELAY_MS
        )
    }

    private fun cancelPendingStart() {
        pendingStartRunnable?.let {
            try {
                mainHandler.removeCallbacks(it)
            } catch (_: Exception) {
            }
        }

        pendingStartRunnable = null
        startScheduled = false
    }

    private fun startCaptureInternal(
        resultCode: Int,
        data: Intent,
        generation: Long
    ) {
        try {
            Log.i(
                TAG,
                "startCapture begin. generation=$generation reason=$activeStartReason operationId=$activeOperationId"
            )

            isCleanupInProgress = true
            cleanupCaptureResources(stopProjection = true)
            isCleanupInProgress = false

            if (generation != lifecycleGeneration) {
                Log.i(
                    TAG,
                    "startCapture aborted after cleanup: stale generation. " +
                            "generation=$generation current=$lifecycleGeneration"
                )
                finishAbortedStart("STALE_GENERATION_AFTER_CLEANUP")
                return
            }

            isStopping = false

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection 획득 실패")

            projection = mediaProjection

            val callbackGeneration = generation
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (callbackGeneration != lifecycleGeneration) {
                        Log.i(
                            TAG,
                            "Stale MediaProjection callback ignored. callbackGeneration=$callbackGeneration current=$lifecycleGeneration"
                        )
                        return
                    }

                    Log.w(TAG, "MediaProjection onStop() called by system")

                    val operationId = activeOperationId
                    if (operationId.isNotBlank()) {
                        AgentOperationStore.fail(
                            operationId,
                            "media_projection_stopped",
                            retryable = true
                        )
                    }

                    stopCaptureAsync(
                        finalState = CaptureStateStore.STATE_IDLE,
                        stopProjection = false,
                        stopSelfAfter = true,
                        reason = "MEDIA_PROJECTION_ON_STOP"
                    )
                }
            }

            projectionCallback = callback
            mediaProjection.registerCallback(callback, mainHandler)

            resetRtspClientTracking()

            val server = RtspH264TcpServer(RTSP_PORT).apply {
                onClientConnected = {
                    handleRtspClientConnected()
                }

                onClientDisconnected = {
                    handleRtspClientDisconnected()
                }

                start()
            }

            rtspServer = server

            val config = selectEncoderConfig()

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                config.width,
                config.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )

                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)

                try {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                } catch (_: Exception) {
                }

                try {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    )
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel31
                    )
                } catch (_: Exception) {
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    } catch (_: Exception) {
                    }
                }
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            val surface = codec.createInputSurface()

            codec.start()

            encoder = codec
            inputSurface = surface

            val display = mediaProjection.createVirtualDisplay(
                "MultiQuest",
                config.width,
                config.height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            virtualDisplay = display

            val ip = AgentStatus.currentIpv4(this) ?: "0.0.0.0"
            val streamGeneration = CaptureStateStore.streamGeneration

            // 중요:
            // 매 캡처 세션마다 RTSP URL을 바꿉니다.
            // WPF가 같은 URL의 오래된 검은 화면 세션을 계속 물고 있는 문제를 줄이기 위한 처리입니다.
            CaptureStateStore.rtspUrl = "rtsp://$ip:$RTSP_PORT/live/$streamGeneration"

            Log.i(
                TAG,
                "Capture started. " +
                        "encoder=${config.width}x${config.height}, " +
                        "fps=${config.fps}, bitrate=${config.bitrate}, " +
                        "rtsp=${CaptureStateStore.rtspUrl}, generation=$generation, " +
                        "streamGeneration=$streamGeneration"
            )

            updateNotification("프레임 대기 중: ${CaptureStateStore.rtspUrl}")

            serviceScope.launch {
                drainEncoder(generation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed", e)

            isCleanupInProgress = false

            val operationId = activeOperationId
            if (operationId.isNotBlank()) {
                AgentOperationStore.fail(
                    operationId,
                    e.message ?: e.javaClass.simpleName,
                    retryable = true
                )
            }

            stopCaptureAsync(
                finalState = CaptureStateStore.STATE_ERROR,
                stopProjection = true,
                stopSelfAfter = true,
                errorMessage = e.message ?: e.javaClass.simpleName,
                reason = "START_CAPTURE_FAILED"
            )
        }
    }

    private fun selectEncoderConfig(): EncoderConfig {
        val requestedWidth = safePositive(CaptureStateStore.width, MAX_MULTI_WIDTH)
        val requestedHeight = safePositive(CaptureStateStore.height, MAX_MULTI_HEIGHT)
        val requestedFps = safePositive(CaptureStateStore.fps, MAX_MULTI_FPS)
        val requestedBitrate = safePositive(CaptureStateStore.bitrate, MAX_MULTI_BITRATE)

        val width = alignEven(minOf(requestedWidth, MAX_MULTI_WIDTH))
        val height = alignEven(minOf(requestedHeight, MAX_MULTI_HEIGHT))
        val fps = minOf(requestedFps, MAX_MULTI_FPS).coerceAtLeast(5)
        val bitrate = minOf(requestedBitrate, MAX_MULTI_BITRATE).coerceAtLeast(300_000)

        Log.i(
            TAG,
            "Encoder config selected. " +
                    "requested=${requestedWidth}x${requestedHeight}/${requestedFps}fps/${requestedBitrate}bps, " +
                    "actual=${width}x${height}/${fps}fps/${bitrate}bps"
        )

        return EncoderConfig(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate
        )
    }

    private fun safePositive(value: Int, fallback: Int): Int {
        return if (value > 0) value else fallback
    }

    private fun alignEven(value: Int): Int {
        return if (value % 2 == 0) value else value - 1
    }

    private fun handleRtspClientConnected() {
        if (isStopping) return

        val clientCountAfter: Int

        synchronized(rtspClientLock) {
            activeRtspClientCount += 1
            clientCountAfter = activeRtspClientCount
        }

        CaptureStateStore.updateRtspClientCount(clientCountAfter)

        val jitter = deviceJitterMs(CLIENT_CONNECT_KEYFRAME_JITTER_RANGE_MS)

        Log.i(
            TAG,
            "RTSP client connected. activeClients=$clientCountAfter, keyframeJitter=${jitter}ms"
        )

        mainHandler.postDelayed({
            requestKeyFrame("rtsp_client_connected_300ms_jittered")
        }, 300L + jitter)

        mainHandler.postDelayed({
            requestKeyFrame("rtsp_client_connected_1200ms_jittered")
        }, 1200L + jitter)

        mainHandler.postDelayed({
            requestKeyFrame("rtsp_client_connected_2200ms_jittered")
        }, 2200L + jitter)

        startPeriodicKeyFrameRequests()
    }

    private fun handleRtspClientDisconnected() {
        val clientCountAfter: Int

        synchronized(rtspClientLock) {
            activeRtspClientCount = max(0, activeRtspClientCount - 1)
            clientCountAfter = activeRtspClientCount
        }

        CaptureStateStore.updateRtspClientCount(clientCountAfter)

        Log.i(TAG, "RTSP client disconnected. activeClients=$clientCountAfter")

        if (clientCountAfter <= 0) {
            stopPeriodicKeyFrameRequests()
        }
    }

    private fun resetRtspClientTracking() {
        synchronized(rtspClientLock) {
            activeRtspClientCount = 0
        }

        CaptureStateStore.updateRtspClientCount(0)

        stopPeriodicKeyFrameRequests()
    }

    private fun startPeriodicKeyFrameRequests() {
        if (keyFrameRequestJob?.isActive == true) return

        keyFrameRequestJob = serviceScope.launch {
            val firstJitter = deviceJitterMs(PERIODIC_KEYFRAME_JITTER_RANGE_MS)

            Log.i(
                TAG,
                "Periodic IDR request job started. " +
                        "interval=${PERIODIC_KEYFRAME_INTERVAL_MS}ms, firstJitter=${firstJitter}ms"
            )

            if (firstJitter > 0) {
                delay(firstJitter)
            }

            while (isActive && !isStopping) {
                val hasClient = synchronized(rtspClientLock) {
                    activeRtspClientCount > 0
                }

                if (!hasClient) {
                    Log.i(TAG, "Periodic IDR request job stopped: no RTSP clients")
                    break
                }

                requestKeyFrame("periodic_idr_jittered")

                val jitter = deviceJitterMs(PERIODIC_KEYFRAME_JITTER_RANGE_MS)
                delay(PERIODIC_KEYFRAME_INTERVAL_MS + jitter)
            }

            Log.i(TAG, "Periodic IDR request job finished")
        }
    }

    private fun stopPeriodicKeyFrameRequests() {
        try {
            keyFrameRequestJob?.cancel()
        } catch (_: Exception) {
        }

        keyFrameRequestJob = null
    }

    private fun drainEncoder(generation: Long) {
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (
                serviceScope.isActive &&
                !isStopping &&
                generation == lifecycleGeneration
            ) {
                val currentEncoder = encoder ?: break

                val index = try {
                    currentEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "MediaCodec is already stopped", e)
                    break
                }

                when {
                    index >= 0 -> {
                        val outputBuffer = currentEncoder.getOutputBuffer(index)

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val isCodecConfig =
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                            val isKeyFrame =
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)

                            if (isCodecConfig) {
                                rtspServer?.setConfigData(bytes)
                            } else {
                                val pts90k = bufferInfo.presentationTimeUs * 90L / 1000L

                                rtspServer?.pushAccessUnit(
                                    accessUnit = bytes,
                                    timestamp90k = pts90k,
                                    isKeyFrame = isKeyFrame,
                                    inSps = null,
                                    inPps = null
                                )

                                val frameNumber = CaptureStateStore.updateFrameStatus()

                                if (frameNumber == 1L) {
                                    Log.i(TAG, "First encoded frame received")
                                    updateNotification("송출 중: ${CaptureStateStore.rtspUrl}")

                                    val operationId = activeOperationId
                                    if (operationId.isNotBlank()) {
                                        AgentOperationStore.complete(
                                            operationId,
                                            "first_encoded_frame",
                                            org.json.JSONObject()
                                                .put("streamGeneration", CaptureStateStore.streamGeneration)
                                                .put("rtspUrl", CaptureStateStore.rtspUrl)
                                        )
                                        CaptureStateStore.completeOperation(operationId)
                                        activeOperationId = ""
                                    }

                                    mainHandler.postDelayed({
                                        requestKeyFrame("first_frame_after_encoder_output")
                                    }, 500L + deviceJitterMs(300L))
                                }
                            }
                        }

                        try {
                            currentEncoder.releaseOutputBuffer(index, false)
                        } catch (e: Exception) {
                            Log.w(TAG, "releaseOutputBuffer failed", e)
                            break
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "End of stream")
                            break
                        }
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = currentEncoder.outputFormat

                        rtspServer?.setFormat(newFormat)

                        Log.i(TAG, "Encoder output format changed: $newFormat")

                        mainHandler.postDelayed({
                            requestKeyFrame("output_format_changed")
                        }, 700L + deviceJitterMs(300L))
                    }

                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 정상: 아직 출력 프레임 없음
                    }

                    else -> {
                        Log.d(TAG, "Encoder output index=$index")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "drainEncoder error", e)

            if (generation == lifecycleGeneration) {
                stopCaptureAsync(
                    finalState = CaptureStateStore.STATE_ERROR,
                    stopProjection = true,
                    stopSelfAfter = true,
                    errorMessage = e.message ?: e.javaClass.simpleName,
                    reason = "DRAIN_ENCODER_ERROR"
                )
            }

            return
        } finally {
            if (
                !isStopping &&
                generation == lifecycleGeneration
            ) {
                stopCaptureAsync(
                    finalState = CaptureStateStore.STATE_IDLE,
                    stopProjection = true,
                    stopSelfAfter = true,
                    reason = "DRAIN_ENCODER_FINALLY"
                )
            }
        }
    }

    private fun requestKeyFrame(reason: String = "manual") {
        try {
            if (isStopping) return

            val now = SystemClock.elapsedRealtime()

            if (now - lastKeyFrameRequestAtMs < MIN_KEYFRAME_REQUEST_INTERVAL_MS) {
                Log.d(
                    TAG,
                    "IDR keyframe request skipped by throttle. reason=$reason"
                )
                return
            }

            val currentEncoder = encoder ?: return

            // 첫 프레임 전 setParameters()는 일부 Quest/Qualcomm encoder에서 실패할 수 있습니다.
            if (CaptureStateStore.encodedFrameCount <= 0L) {
                Log.d(
                    TAG,
                    "IDR keyframe request skipped before first frame. reason=$reason"
                )
                return
            }

            currentEncoder.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )

            lastKeyFrameRequestAtMs = now

            Log.d(TAG, "IDR keyframe requested. reason=$reason")
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyFrame ignored. reason=$reason", e)
        }
    }

    private fun deviceJitterMs(rangeMs: Long): Long {
        if (rangeMs <= 0L) return 0L

        return try {
            val androidId = Settings.Secure.getString(
                applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            ).orEmpty()

            val seed = if (androidId.isNotBlank()) {
                androidId
            } else {
                applicationContext.packageName
            }

            val positiveHash = seed.hashCode().toLong() and 0x7FFF_FFFFL
            positiveHash % rangeMs
        } catch (_: Exception) {
            System.currentTimeMillis() % rangeMs
        }
    }

    private fun stopCaptureAsync(
        finalState: String = CaptureStateStore.STATE_IDLE,
        stopProjection: Boolean = true,
        stopSelfAfter: Boolean = false,
        errorMessage: String = "",
        reason: String = ""
    ) {
        cancelPendingStart()

        if (stopSelfAfter) {
            stopSelfWhenCleanupDone = true
        }

        if (isCleanupInProgress && !isStopping) {
            val cancelledGeneration = bumpLifecycleGeneration()
            isStopping = true
            Log.i(
                TAG,
                "stopCapture requested during cleanup. reason=$reason generation=$cancelledGeneration"
            )
            return
        }

        if (isStopping) {
            Log.i(
                TAG,
                "stopCapture merged: already stopping. reason=$reason stopSelfAfter=$stopSelfAfter"
            )
            return
        }

        val stopGeneration = bumpLifecycleGeneration()
        isStopping = true
        isCleanupInProgress = true

        Log.i(
            TAG,
            "Stopping capture async. finalState=$finalState, " +
                    "stopProjection=$stopProjection, stopSelfAfter=$stopSelfAfter, " +
                    "reason=$reason, generation=$stopGeneration"
        )

        stopPeriodicKeyFrameRequests()

        synchronized(rtspClientLock) {
            activeRtspClientCount = 0
        }

        CaptureStateStore.updateRtspClientCount(0)

        serviceScope.launch(captureDispatcher) {
            cleanupCaptureResources(stopProjection = stopProjection)

            mainHandler.post {
                isCleanupInProgress = false

                if (stopGeneration != lifecycleGeneration) {
                    Log.i(
                        TAG,
                        "Stop finalization skipped: stale generation. " +
                                "stopGeneration=$stopGeneration current=$lifecycleGeneration"
                    )
                    return@post
                }

                when (finalState) {
                    CaptureStateStore.STATE_STOPPED -> {
                        CaptureStateStore.markStopped()
                    }

                    CaptureStateStore.STATE_ERROR -> {
                        CaptureStateStore.setError(
                            errorMessage.ifBlank { "capture_error" }
                        )
                    }

                    else -> {
                        CaptureStateStore.clearToIdle()
                    }
                }

                val operationId = activeOperationId
                if (
                    operationId.isNotBlank() &&
                    AgentOperationStore.isPending(operationId)
                ) {
                    AgentOperationStore.fail(
                        operationId,
                        errorMessage.ifBlank { reason.ifBlank { "capture_stopped" } },
                        retryable = finalState != CaptureStateStore.STATE_STOPPED
                    )
                }

                activeOperationId = ""
                activeStartReason = ""
                isServiceAlive = false
                isStopping = false

                if (stopSelfWhenCleanupDone) {
                    stopSelfWhenCleanupDone = false
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun finishAbortedStart(reason: String) {
        val operationId = activeOperationId
        if (operationId.isNotBlank() && AgentOperationStore.isPending(operationId)) {
            AgentOperationStore.fail(
                operationId,
                reason,
                retryable = true
            )
        }

        activeOperationId = ""
        activeStartReason = ""
        isCleanupInProgress = false
        isServiceAlive = false
        isStopping = false
        CaptureStateStore.clearToIdle()

        mainHandler.post {
            if (stopSelfWhenCleanupDone) {
                stopSelfWhenCleanupDone = false
            }
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun cleanupCaptureResources(stopProjection: Boolean) {
        val server = rtspServer
        rtspServer = null

        try {
            server?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "RTSP stop error", e)
        }

        val display = virtualDisplay
        virtualDisplay = null

        try {
            display?.release()
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay release error", e)
        }

        val codec = encoder
        encoder = null

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec stop error", e)
        }

        try {
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec release error", e)
        }

        val surface = inputSurface
        inputSurface = null

        try {
            surface?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Surface release error", e)
        }

        val localProjection = projection
        val localCallback = projectionCallback

        projection = null
        projectionCallback = null

        try {
            if (localCallback != null && localProjection != null) {
                localProjection.unregisterCallback(localCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Projection callback unregister error", e)
        }

        if (stopProjection) {
            try {
                localProjection?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Projection stop error", e)
            }
        }

        Log.i(TAG, "Capture resources cleaned. stopProjection=$stopProjection")
    }

    private fun startAsForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            notificationManager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Stream",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiQuest Stream")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiQuest Stream")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        cancelPendingStart()

        isStopping = true
        isCleanupInProgress = true

        stopPeriodicKeyFrameRequests()

        try {
            cleanupCaptureResources(stopProjection = true)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy cleanup failed", e)
        } finally {
            isCleanupInProgress = false
            isServiceAlive = false
            isStopping = false
        }

        val operationId = activeOperationId
        if (operationId.isNotBlank() && AgentOperationStore.isPending(operationId)) {
            AgentOperationStore.fail(
                operationId,
                "capture_service_destroyed",
                retryable = true
            )
        }
        activeOperationId = ""
        activeStartReason = ""

        serviceScope.cancel()

        try {
            captureDispatcher.close()
        } catch (_: Exception) {
        }

        try {
            captureExecutor.shutdownNow()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}