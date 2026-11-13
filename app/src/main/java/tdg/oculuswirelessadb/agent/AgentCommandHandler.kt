package tdg.oculuswirelessadb.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import tdg.oculuswirelessadb.CapturePermissionActivity
import tdg.oculuswirelessadb.IdleShieldActivity
import tdg.oculuswirelessadb.KeepAwakeActivity
import tdg.oculuswirelessadb.MainActivity
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


data class AgentCommandResult(
    val httpCode: Int,
    val body: JSONObject
)

object AgentCommandHandler {

    private const val TAG = "MQ-CMD"
    private const val HANDLER_BUILD_ID =
        "vrshell-control-broadcast-no-panel-2026.08.04-r10"

    private const val DEFAULT_UNITY_ACTIVITY =
        "com.unity3d.player.UnityPlayerActivity"

    // 콘텐츠 콜드 스타트가 약 9~10초 걸리는 Quest에서도
    // WPF 대기 없이 Agent 로컬 재전송으로 포착할 수 있도록 확장합니다.
    private const val MAX_RETRY_COUNT = 9
    private const val MAX_RETRY_INTERVAL_MS = 2_000
    private const val MAX_HOME_DELAY_MS = 3_000
    private const val ACTIVE_CAPTURE_TRANSITION_TIMEOUT_MS = 35_000L
    private const val APP_LAUNCH_VERIFY_TIMEOUT_MS = 35_000L
    private const val WAKE_VERIFY_DELAY_MS = 1_000L
    private const val TRANSIENT_SCREEN_WAKE_MS = 3_000L
    private const val ACTIVITY_DISPATCH_TIMEOUT_MS = 5_000L
    private const val APP_LAUNCH_GUARD_MS = 20_000L
    private const val FORCE_RESTART_STOP_COUNT = 2
    private const val FORCE_RESTART_STOP_INTERVAL_MS = 400L

    private const val STOP_ALL_MIN_RETRY_COUNT = 5
    private const val STOP_ALL_MAX_RETRY_INTERVAL_MS = 500
    private const val STOP_ALL_DEDUPE_WINDOW_MS = 4_000L

    /**
     * 동일 package의 중복 launch가 첫 XR 세션 초기화 중 다시 task를 지우지 않게 합니다.
     */
    private const val LAUNCH_DEDUPE_WINDOW_MS = 8_000L

    private const val APP_COMMAND_DEFAULT_RETRY_COUNT = 4
    private const val APP_COMMAND_DEFAULT_RETRY_INTERVAL_MS = 700
    private const val APP_COMMAND_ACK_GRACE_MS = 3_000L
    private const val APP_COMMAND_REQUEST_TTL_MS = 60_000L
    private const val MAX_REQUEST_ID_LENGTH = 160

    const val ACTION_STORYWING_STOP =
        "com.storywing.multiquest.ACTION_STOP"
    const val ACTION_STORYWING_COMMAND =
        "com.storywing.multiquest.ACTION_COMMAND"

    private const val XR_CODING_PACKAGE =
        "com.StoryWing.XR_Coding"
    private const val XR_ENGLISH_PACKAGE =
        "com.StoryWing.Storywing_Class"
    private const val XR_ENGLISH_PACKAGE_ALT =
        "com.StoryWing.StorywingClass"

    private val PRIORITY_STOP_PACKAGES = listOf(
        XR_CODING_PACKAGE,
        XR_ENGLISH_PACKAGE,
        XR_ENGLISH_PACKAGE_ALT
    )

    private val appCommandLock = ReentrantLock(true)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * fastDispatch=true 요청은 HTTP 응답 스레드를 막지 않고
     * 이 단일 실행 큐에서 순서대로 Activity 전환을 처리합니다.
     */
    private val fastLaunchExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "mq-fast-launch"
            ).apply {
                isDaemon = true
            }
        }

    /**
     * 종료 재시도는 HTTP 응답 스레드를 막지 않고 이 전용 큐에서 처리합니다.
     * 새 요청이 이전 시퀀스를 취소하지 않으며 순서대로 끝까지 실행합니다.
     */
    private val stopSequenceExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "mq-stop-sequence"
            ).apply {
                isDaemon = true
            }
        }

    /**
     * 콘텐츠 명령은 HTTP 응답과 분리해 0ms/700ms/1400ms/2100ms에 재전송합니다.
     * 앱이 아직 시작 중이어도 manifest receiver가 저장한 뒤 Unity에서 소비할 수 있습니다.
     */
    private val appCommandScheduler =
        ScheduledThreadPoolExecutor(
            1
        ) { runnable ->
            Thread(
                runnable,
                "mq-app-command"
            ).apply {
                isDaemon = true
            }
        }.apply {
            setRemoveOnCancelPolicy(true)
        }

    private data class AppCommandRequestEntry(
        val operationId: String,
        val expiresAtElapsedMs: Long
    )

    private data class StopAllReservation(
        val operationId: String,
        val sequenceGeneration: Long,
        val reused: Boolean
    )

    private val appCommandRequestLock = Any()
    private val appCommandRequests =
        LinkedHashMap<String, AppCommandRequestEntry>()

    private val launchDispatchGeneration =
        AtomicLong(0L)

    private val launchRequestLock =
        Any()

    private var lastLaunchPackageName: String =
        ""

    private var lastLaunchAcceptedAtElapsedMs: Long =
        0L

    private var lastLaunchOperationId: String =
        ""

    /**
     * STOP에서 예약한 IdleShield 표시가 새 StoryWing launch 뒤에 실행되는 것을
     * 차단하는 별도 generation입니다.
     *
     * STOP/STOP_ALL이 Shield를 예약할 때 증가하고,
     * 새 launch가 접수될 때도 증가하여 과거 예약을 무효화합니다.
     */
    private val agentUiTransitionGeneration =
        AtomicLong(0L)

    private val stopSequenceGeneration =
        AtomicLong(0L)

    private val stopAllRequestLock =
        Any()

    private var activeStopAllOperationId: String =
        ""

    private var activeStopAllAcceptedAtElapsedMs: Long =
        0L


    /**
     * 전체 종료 직후 늦게 도착한 launch HTTP 요청이 앱을 다시 열지 못하게 하는
     * 짧은 차단 구간입니다. 0초/1초/2초 STOP 시퀀스가 끝나면 자동 해제됩니다.
     */
    private val stopBarrierUntilElapsedMs =
        AtomicLong(0L)

    private val STORYWING_PACKAGES = listOf(
        "com.StoryWing.EnglishTown",
        "com.StoryWing.AlphabatApp",
        "com.StoryWing.Ocean_Adventure",
        "com.StoryWing.OceanAdventure",
        "com.StoryWing.Korea_History",
        "com.StoryWing.KoreaHistory2",
        "com.StoryWing.Solar_System",
        "com.StoryWing.SpaceApp",
        "com.StoryWing.Fire_Safety",
        "com.StoryWing.FirepreventionApp",
        "com.StoryWing.Jurassic",
        "com.StoryWing.BrainPop",
        "com.StoryWing.XR_BrainTraining",
        "com.StoryWing.SmartFarm",
        "com.StoryWing.GyeongjuMR",
        "com.StoryWing.Museum",
        "com.StoryWing.XR_Museum",
        "com.StoryWing.World_Travel",
        "com.StoryWing.XR_WorldMap",
        "com.StoryWing.OceanAdventure_ViewVer",
        "com.StoryWing.SpaceAppTest",
        "com.StoryWing.XR_Coding",
        "com.StoryWing.Storywing_Class",
        "com.StoryWing.StorywingClass",
        "com.StoryWing.XRMindSteps",
        "com.StoryWing.App1",
        "com.StoryWing.App2",
        "com.StoryWing.App3",
        "com.StoryWing.App4",
        "com.StoryWing.App5",
        "com.StoryWing.App6",
        "com.StoryWing.App7",
        "com.StoryWing.App8",
        "com.StoryWing.App9",
        "com.StoryWing.App10",
        "com.StoryWing.App11",
        "com.StoryWing.App12",
        "com.StoryWing.App13",
        "com.StoryWing.App14",
        "com.StoryWing.App15",
        "com.StoryWing.App16",
        "com.StoryWing.App17",
        "com.StoryWing.App18",
        "com.StoryWing.App19",
        "com.StoryWing.App20"
    )

    fun handle(
        context: Context,
        path: String,
        body: String
    ): AgentCommandResult {
        return try {
            when (path) {
                "/command/launch" -> handleLaunch(context, body)
                "/command/stop" -> handleStop(context, body)
                "/command/stopAllStoryWing" ->
                    handleStopAllStoryWing(context, body)
                "/command/home" -> handleHome(context)

                "/command/restartCapture" ->
                    handleRestartCapture(context)
                "/command/startCaptureUi" ->
                    handleStartCaptureUi(context)

                "/command/appCommand" ->
                    handleAppCommand(context, body)

                "/command/wake" -> handleWake(context, body)
                "/command/keepAwake" ->
                    handleKeepAwake(context, body)
                "/command/showKeepAwake" ->
                    handleShowKeepAwake(context)
                "/command/hideKeepAwake" ->
                    handleHideKeepAwake()

                "/command/setDeviceName" ->
                    handleSetDeviceName(context, body)

                else -> error(
                    code = "unknown_command",
                    message = "Unknown command path: $path",
                    httpCode = 404
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "command failed: $path", e)
            error(
                code = "exception",
                message = e.message ?: e.javaClass.simpleName,
                httpCode = 500
            )
        }
    }

    private fun handleSetDeviceName(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val newName = json.optString("deviceName", "").trim()

        if (newName.isBlank()) {
            return error(
                "bad_request",
                "deviceName is required",
                400
            )
        }

        val saved = AgentNameStore.setDeviceName(context, newName)

        return if (saved) {
            completed(
                JSONObject()
                    .put("deviceName", AgentNameStore.getDeviceName(context))
                    .put("deviceNameSource", "custom")
                    .put("deviceNameUpdatedAtMs", AgentNameStore.getUpdatedAtMs(context))
            )
        } else {
            error(
                code = "invalid_device_name",
                message = "deviceName is blank or invalid",
                httpCode = 400
            )
        }
    }

    private fun handleLaunch(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()

        Log.i(
            TAG,
            "handleLaunch build=$HANDLER_BUILD_ID"
        )

        val packageName =
            json.optString(
                "packageName",
                ""
            ).trim()

        val activityName =
            json.optString(
                "activityName",
                DEFAULT_UNITY_ACTIVITY
            ).trim()

        val forceRestart =
            json.optBoolean(
                "forceRestart",
                false
            )

        val stopOthers =
            json.optBoolean(
                "stopOthers",
                true
            )

        val stopRetryCount =
            clampRetryCount(
                json.optInt(
                    "stopRetryCount",
                    1
                )
            )

        val stopRetryIntervalMs =
            clampInterval(
                json.optInt(
                    "stopRetryIntervalMs",
                    100
                )
            )

        val fastDispatch =
            json.optBoolean(
                "fastDispatch",
                false
            )

        /*
         * 과거 focus pulse / 대상 Activity 재호출 구현이 남아 있더라도
         * VRShell 전환을 다시 흔들지 않도록 이 빌드에서는 강제로 끕니다.
         */
        val focusGuard =
            false

        val extrasJson =
            json.optJSONObject("extras")
                ?.toString()

        if (packageName.isBlank()) {
            return error(
                "bad_request",
                "packageName is required",
                400
            )
        }

        /*
         * 빠른 전체 실행에서 첫 번째 후보가 설치되지 않은 경우
         * WPF가 즉시 다음 후보를 시도할 수 있도록 Activity 큐 등록 전에
         * 패키지 설치 여부를 확인합니다.
         */
        if (!isPackageInstalled(
                context,
                packageName
            )
        ) {
            return error(
                code = "package_not_installed",
                message =
                    "Package is not installed: " +
                            packageName,
                httpCode = 404,
                retryable = false
            )
        }

        /*
         * 전체 종료 시퀀스가 진행 중이면 늦게 도착한 launch 요청을 거부합니다.
         * 이렇게 해야 0초 STOP 뒤 지연된 startActivity가 앱을 다시 여는 경쟁 상태를
         * 1초/2초 재시도와 함께 확실하게 차단할 수 있습니다.
         */
        val stopBarrierRemainingMs =
            stopBarrierUntilElapsedMs.get() -
                    SystemClock.elapsedRealtime()

        if (stopBarrierRemainingMs > 0L) {
            return retryableError(
                code = "stop_sequence_in_progress",
                message =
                    "App launch is temporarily blocked while " +
                            "the stop sequence is running",
                retryAfterMs = stopBarrierRemainingMs
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
        }

        val nowElapsedMs =
            SystemClock.elapsedRealtime()

        synchronized(
            launchRequestLock
        ) {
            val duplicate =
                lastLaunchOperationId.isNotBlank() &&
                        lastLaunchPackageName.equals(
                            packageName,
                            ignoreCase = true
                        ) &&
                        nowElapsedMs -
                        lastLaunchAcceptedAtElapsedMs <
                        LAUNCH_DEDUPE_WINDOW_MS

            if (duplicate) {
                Log.w(
                    TAG,
                    "Duplicate launch merged. " +
                            "package=$packageName " +
                            "operationId=$lastLaunchOperationId " +
                            "ageMs=${nowElapsedMs - lastLaunchAcceptedAtElapsedMs}"
                )

                return accepted(
                    operationId =
                        lastLaunchOperationId,
                    extra = JSONObject()
                        .put(
                            "packageName",
                            packageName
                        )
                        .put(
                            "duplicateMerged",
                            true
                        )
                        .put(
                            "launchMode",
                            "VRSHELL_NATIVE"
                        )
                )
            }
        }

        /*
         * 과거 STOP/STOP_ALL에서 Main Looper에 예약한 Agent UI hide 작업을 무효화합니다.
         *
         * 이 처리가 없으면:
         * STOP 응답 지연 재시도 → Shield post 예약 → 새 앱 launch →
         * 늦은 Shield post 실행 순서로 Agent/Quest UI가 다시 전면에 올라올 수 있습니다.
         */
        val launchUiGeneration =
            agentUiTransitionGeneration
                .incrementAndGet()

        Log.i(
            TAG,
            "Stale stop UI work invalidated for launch. " +
                    "package=$packageName uiGeneration=$launchUiGeneration"
        )

        /*
         * R2 회귀 롤백:
         * 외부 앱 실행과 동시에 무기한 캡처 pause/AgentService 재시작을 만들지 않습니다.
         * 기존 STOP이 걸어 둔 pause만 해제하고, 첫 프레임 보호는 유한 guard로만 처리합니다.
         */
        AutoCapturePauseStore.resume(
            context.applicationContext
        )

        LaunchFocusStabilizer.cancel(
            "superseded_by_new_launch_request"
        )

        AgentOperationStore
            .cancelPendingLaunches()

        val generation =
            launchDispatchGeneration
                .incrementAndGet()

        AppLaunchGuard.begin(
            packageName,
            APP_LAUNCH_GUARD_MS
        )

        val operationId =
            AgentOperationStore.begin(
                type =
                    AgentOperationStore
                        .TYPE_LAUNCH,
                target = packageName,
                reason =
                    when {
                        fastDispatch ->
                            "HTTP_LAUNCH_FAST"
                        forceRestart ->
                            "HTTP_LAUNCH_FORCE_RESTART"
                        else ->
                            "HTTP_LAUNCH"
                    }
            )

        synchronized(
            launchRequestLock
        ) {
            lastLaunchPackageName =
                packageName

            lastLaunchAcceptedAtElapsedMs =
                nowElapsedMs

            lastLaunchOperationId =
                operationId
        }

        LastLaunchedAppStore.save(
            context = context,
            packageName = packageName,
            activityName = activityName,
            extrasJson = extrasJson
        )

        if (fastDispatch) {
            /*
             * HTTP에는 즉시 202 Accepted를 반환합니다.
             * 실제 startActivity와 APP_STATE_ACTIVE 검증은 아래 실행 큐에서
             * 계속 진행되며 operationId로 상태를 조회할 수 있습니다.
             */
            AgentOperationStore.markDispatched(
                operationId,
                "VRShell native launch queued"
            )

            fastLaunchExecutor.execute {
                if (
                    generation !=
                    launchDispatchGeneration.get()
                ) {
                    /*
                     * 새 launch가 같은 package로 들어온 경우에도 현재 guard를
                     * 지우면 안 됩니다. 최신 launch/STOP 경로가 guard를 소유합니다.
                     */
                    AgentOperationStore.cancel(
                        operationId,
                        "launch_superseded_before_dispatch"
                    )

                    return@execute
                }

                val taskExtras =
                    extrasJson
                        ?.let { saved ->
                            try {
                                JSONObject(saved)
                            } catch (_: Exception) {
                                null
                            }
                        }

                val launched =
                    performLaunchDispatch(
                        context =
                            context.applicationContext,
                        packageName =
                            packageName,
                        activityName =
                            activityName,
                        extras =
                            taskExtras,
                        forceRestart =
                            forceRestart,
                        stopOthers =
                            stopOthers,
                        stopRetryCount =
                            stopRetryCount,
                        stopRetryIntervalMs =
                            stopRetryIntervalMs,
                        expectedGeneration =
                            generation
                    )

                finalizeLaunchDispatch(
                    context =
                        context.applicationContext,
                    operationId =
                        operationId,
                    packageName =
                        packageName,
                    activityName =
                        activityName,
                    focusGuard =
                        focusGuard,
                    launched =
                        launched,
                    expectedGeneration =
                        generation
                )
            }

            return accepted(
                operationId = operationId,
                extra = JSONObject()
                    .put(
                        "packageName",
                        packageName
                    )
                    .put(
                        "fastDispatch",
                        true
                    )
                    .put(
                        "focusGuard",
                        focusGuard
                    )
                    .put(
                        "verification",
                        "APP_STATE_ACTIVE"
                    )
                    .put(
                        "timeoutMs",
                        APP_LAUNCH_VERIFY_TIMEOUT_MS
                    )
            )
        }

        val extras =
            extrasJson
                ?.let { saved ->
                    try {
                        JSONObject(saved)
                    } catch (_: Exception) {
                        null
                    }
                }

        val launched =
            performLaunchDispatch(
                context = context,
                packageName = packageName,
                activityName = activityName,
                extras = extras,
                forceRestart = forceRestart,
                stopOthers = stopOthers,
                stopRetryCount = stopRetryCount,
                stopRetryIntervalMs =
                    stopRetryIntervalMs,
                expectedGeneration =
                    generation
            )

        finalizeLaunchDispatch(
            context =
                context.applicationContext,
            operationId =
                operationId,
            packageName =
                packageName,
            activityName =
                activityName,
            focusGuard =
                focusGuard,
            launched =
                launched,
            expectedGeneration =
                generation
        )

        if (!launched) {
            return error(
                code = "launch_failed",
                message =
                    "Could not dispatch activity launch",
                httpCode = 500,
                operationId = operationId,
                retryable = true
            )
        }

        return accepted(
            operationId = operationId,
            extra = JSONObject()
                .put(
                    "packageName",
                    packageName
                )
                .put(
                    "fastDispatch",
                    false
                )
                .put(
                    "focusGuard",
                    focusGuard
                )
                .put(
                    "verification",
                    "APP_STATE_ACTIVE"
                )
                .put(
                    "timeoutMs",
                    APP_LAUNCH_VERIFY_TIMEOUT_MS
                )
        )
    }

    private fun performLaunchDispatch(
        context: Context,
        packageName: String,
        activityName: String,
        extras: JSONObject?,
        forceRestart: Boolean,
        stopOthers: Boolean,
        stopRetryCount: Int,
        stopRetryIntervalMs: Int,
        expectedGeneration: Long
    ): Boolean {
        if (
            expectedGeneration !=
            launchDispatchGeneration.get()
        ) {
            return false
        }

        if (forceRestart) {
            /*
             * 중요:
             * 레거시 앱의 ACTION_STOP은 Application.Quit + Process.Kill까지 실행합니다.
             * 직전 XR 프로세스를 죽인 직후 새 OpenXR 앱을 실행하면 Quest compositor
             * 전환과 겹쳐 오디오는 나오지만 영상이 Meta 로딩 공간에 머물 수 있습니다.
             *
             * 따라서 forceRestart도 여기서는 강제 종료하지 않고 launcher Intent를
             * 다시 전송하는 안전 재전면화로 처리합니다. 확실한 종료는 별도의
             * STOP/STOP_ALL 명령에서만 수행합니다.
             */
            Log.w(
                TAG,
                "forceRestart requested; using safe launcher redispatch " +
                        "without pre-launch process kill. package=$packageName"
            )
        }

        return appCommandLock.withLock {
            if (
                expectedGeneration !=
                launchDispatchGeneration.get()
            ) {
                return@withLock false
            }

            if (stopOthers) {
                /*
                 * 이전 구현은 새 XR 앱을 띄우기 직전에 다른 XR 앱들에 STOP을 보내고
                 * 수백 ms 안에 다음 앱을 시작했습니다. 레거시 앱의 Process.Kill과
                 * Quest OpenXR runtime 정리가 겹치면 오디오는 재생되지만 영상 세션이
                 * 시작되지 않는 무한 로딩이 발생할 수 있습니다.
                 *
                 * 새 앱 launch는 기존 앱을 강제 종료하지 않고 대상 task를 전면화합니다.
                 * 실제 종료는 WPF STOP/STOP_ALL에서만 수행합니다.
                 */
                Log.i(
                    TAG,
                    "stopOthers ignored for safe XR handoff. " +
                            "package=$packageName"
                )
            }

            /*
             * stopAll이 lock을 기다리는 동안 generation을 올릴 수 있으므로
             * 실제 startActivity 직전에 반드시 한 번 더 확인합니다.
             */
            if (
                expectedGeneration !=
                launchDispatchGeneration.get()
            ) {
                return@withLock false
            }

            /*
             * R2의 pre-launch Activity finish + settle 대기를 제거합니다.
             * Unity/OpenXR Activity 전후의 Agent task 조작 없이 대상 Activity를 한 번만 실행합니다.
             */
            launchApp(
                context = context,
                packageName = packageName,
                activityName = activityName,
                extras = extras,
                forceRestart = forceRestart
            )
        }
    }

    private fun finalizeLaunchDispatch(
        context: Context,
        operationId: String,
        packageName: String,
        activityName: String,
        focusGuard: Boolean,
        launched: Boolean,
        expectedGeneration: Long
    ) {
        val currentGeneration =
            launchDispatchGeneration.get()

        /*
         * 핵심 경쟁 수정:
         *
         * 구버전은 startActivity가 끝난 뒤 generation을 다시 확인하지 않고
         * 무조건 LaunchFocusStabilizer.arm()을 호출했습니다.
         *
         * 예:
         * 1) 한국사 launch 작업이 이미 실행 중
         * 2) 사용자가 박물관 launch 요청
         * 3) generation은 박물관으로 변경
         * 4) 늦게 끝난 한국사 finalize가 다시 한국사 focus guard를 arm
         * 5) 박물관이 떠도 한국사가 다시 전면으로 올라옴
         *
         * 이제 늦게 완료된 과거 launch는 상태만 취소하며,
         * 다른 XR 앱 launch 중 Process.Kill을 유발하는 STOP을 보내지 않습니다.
         */
        if (expectedGeneration != currentGeneration) {
            /*
             * 최신 launch가 같은 package일 수 있으므로 stale finalize가
             * AppLaunchGuard를 지우지 않습니다.
             */
            LaunchFocusStabilizer.cancelForPackage(
                packageName = packageName,
                reason = "stale_launch_finalize"
            )

            /*
             * stale Activity를 여기서 STOP하지 않습니다.
             * 다른 OpenXR 앱의 launch와 Process.Kill이 겹치는 것이 오디오-only
             * 무한 로딩의 직접적인 회귀 원인이 될 수 있기 때문입니다.
             * 최신 launch가 자신의 launcher Intent로 최종 전면 task를 결정합니다.
             */

            AgentOperationStore.cancel(
                operationId,
                "launch_superseded_after_dispatch"
            )

            Log.w(
                TAG,
                "Stale launch finalize suppressed. " +
                        "package=$packageName " +
                        "expectedGeneration=$expectedGeneration " +
                        "currentGeneration=$currentGeneration"
            )

            return
        }

        if (!launched) {
            AppLaunchGuard.clearFor(
                packageName
            )

            LaunchFocusStabilizer.cancelForPackage(
                packageName = packageName,
                reason = "activity_launch_dispatch_failed"
            )

            AgentOperationStore.fail(
                operationId,
                "activity_launch_dispatch_failed",
                retryable = true
            )

            LastLaunchedAppStore.markClosed(
                context = context,
                packageName = packageName,
                reason =
                    "ACTIVITY_LAUNCH_DISPATCH_FAILED"
            )

            AutoCapturePauseStore.resume(
                context.applicationContext
            )

            clearLaunchDedupe(
                "activity_launch_dispatch_failed"
            )

            return
        }

        AgentOperationStore.markDispatched(
            operationId,
            "activity launch dispatched"
        )

        if (focusGuard) {
            LaunchFocusStabilizer.arm(
                context = context.applicationContext,
                packageName = packageName,
                activityName = activityName
            )
        }

        /*
         * Coding/English는 APP_STATE_ACTIVE를 보내므로 실제 ACTIVE를 확인합니다.
         * 기존 일반 앱 10개는 ACK가 없으므로 dispatch 성공을 완료로 처리합니다.
         * 35초 뒤 정상 실행 앱을 FAILED/CLOSED로 덮어쓰지 않습니다.
         */
        if (supportsAppStateVerification(packageName)) {
            AgentOperationStore.markDispatched(
                operationId,
                "activity launch dispatched; waiting APP_STATE_ACTIVE"
            )

            scheduleLaunchVerification(
                context = context.applicationContext,
                operationId = operationId,
                packageName = packageName
            )
        } else {
            AgentOperationStore.complete(
                operationId,
                "legacy activity launch dispatched",
                JSONObject()
                    .put("packageName", packageName)
                    .put("verification", "DISPATCH_ONLY")
                    .put("launchGuardMs", APP_LAUNCH_GUARD_MS)
            )

            /*
             * 레거시 일반 앱은 APP_STATE_ACTIVE를 보내지 않습니다.
             * 여기서 guard를 즉시 지우면 AgentService/MainActivity가 투명
             * CapturePermissionActivity를 XR 앱 위로 열 수 있으므로 시간 만료까지 유지합니다.
             */
            Log.i(
                TAG,
                "Legacy launch guard retained until timeout. " +
                        "package=$packageName remainingMs=${AppLaunchGuard.remainingMs()}"
            )
        }

        Log.i(
            TAG,
            "Launch finalized. " +
                    "package=$packageName generation=$expectedGeneration " +
                    "focusGuard=$focusGuard " +
                    "verification=" +
                    if (supportsAppStateVerification(packageName)) {
                        "APP_STATE_ACTIVE"
                    } else {
                        "DISPATCH_ONLY"
                    }
        )
    }

    private fun supportsAppStateVerification(
        packageName: String
    ): Boolean {
        return packageName.equals(
            XR_CODING_PACKAGE,
            ignoreCase = true
        ) ||
                packageName.equals(
                    XR_ENGLISH_PACKAGE,
                    ignoreCase = true
                ) ||
                packageName.equals(
                    XR_ENGLISH_PACKAGE_ALT,
                    ignoreCase = true
                )
    }

    private fun isPackageInstalled(
        context: Context,
        packageName: String
    ): Boolean {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager
                .getApplicationInfo(
                    packageName,
                    0
                )

            true
        } catch (
            _: PackageManager
            .NameNotFoundException
        ) {
            false
        } catch (e: Exception) {
            /*
             * 패키지 가시성 또는 제조사 API 예외로 실제 설치 앱을
             * 잘못 거부하지 않도록 예외 시에는 기존 launch 경로를 허용합니다.
             */
            Log.w(
                TAG,
                "Package preflight failed; " +
                        "launch will still be attempted. " +
                        "package=$packageName",
                e
            )
            true
        }
    }

    /**
     * 마지막으로 무한 로딩 빈도가 낮았던 기존 실행 경로로 롤백합니다.
     *
     * - WPF가 지정한 UnityPlayerActivity를 정확히 한 번 실행
     * - NEW_TASK + CLEAR_TOP + SINGLE_TOP 사용
     * - 실행 전후 Agent Activity finish/reorder 없음
     * - explicit 실행이 실패한 경우에만 package launcher Intent fallback
     */
    /**
     * Quest VR 앱을 PendingIntent가 아닌 Context.startActivity()로 직접 실행합니다.
     *
     * R3의 API 34 경로는 PendingIntent.send()를 사용했습니다.
     * Android Activity는 시작됐지만 Meta VR shell과 OpenXR compositor의
     * 전면 세션 hand-off가 완료되지 않아, 오디오는 재생되면서
     * Meta의 점 3개 로딩 화면에 고정될 수 있었습니다.
     *
     * 이 구현은 무한 로딩이 빈번해지기 전의 단순 실행 경로로 복원합니다.
     *
     * - explicit Unity Activity 우선
     * - FLAG_ACTIVITY_NEW_TASK만 기본 적용
     * - CLEAR_TOP / SINGLE_TOP 사용 금지
     * - PendingIntent 사용 금지
     * - forceRestart일 때만 CLEAR_TASK 사용
     * - explicit 실행 실패 시 package launcher Intent를 직접 실행
     */
    /**
     * 대상 Unity/OpenXR task를 새 root로 다시 생성해 XR 세션 재사용을 차단합니다.
     *
     * R5는 WPF fast launch의 forceRestart=false를 그대로 받아 NEW_TASK만 사용했습니다.
     * 이미 남아 있는 UnityPlayerActivity/task가 재사용되면 Android/Unity 오디오는
     * 동작하지만 OpenXR 세션이 VISIBLE/FOCUSED로 다시 올라오지 못할 수 있습니다.
     *
     * Intent.makeRestartActivityTask:
     * - ACTION_MAIN
     * - CATEGORY_LAUNCHER
     * - FLAG_ACTIVITY_NEW_TASK
     * - FLAG_ACTIVITY_CLEAR_TASK
     *
     * 를 구성합니다. Context.startActivity()는 정확히 한 번만 호출합니다.
     */
    /**
     * Quest Shell의 앱 전환 source/launch association을 보존해 실행합니다.
     *
     * 로그에서 R6 application-context launch는:
     * - sourceRecord=null
     * - launch source=(unknown)
     * - app launchId=""
     *
     * 로 기록됐습니다. 반면 앱 자체는 XR_SESSION_STATE_FOCUSED에 도달하고
     * 72 FPS로 프레임을 제출했지만 Meta interstitial은 5/10/15초까지
     * 계속 유지됐습니다.
     *
     * 이번 정책:
     * 1. 실제 package launcher component 사용
     * 2. ACTION_MAIN + CATEGORY_LAUNCHER
     * 3. task 제어 flag 없음
     * 4. 살아 있는 Agent MainActivity.startActivity()로 실행
     * 5. MainActivity가 없을 때만 applicationContext + NEW_TASK fallback
     */
    /**
     * Quest의 공식 VRShell 전환 경로로 immersive 앱을 실행합니다.
     *
     * 직접 UnityPlayerActivity를 startActivity하면 Quest Shell이 이미 시작된
     * Activity를 뒤늦게 가로채며 다음 상태가 생길 수 있습니다.
     *
     * - launch source=(unknown)
     * - app clientLaunchId=[]
     * - 앱은 XR_SESSION_STATE_FOCUSED 및 72FPS까지 정상
     * - Meta immersive interstitial만 종료되지 않음
     *
     * 현재 Quest용 오픈소스 런처가 VR 앱에 사용하는 경로와 동일하게:
     *
     * action    = com.oculus.vrshell.intent.action.LAUNCH
     * category  = android.intent.category.LAUNCHER
     * data      = apk://<target package>
     * component = com.oculus.vrshell/.MainActivity
     *
     * 로 VRShell에 launch 소유권을 먼저 넘깁니다.
     *
     * stage/lesson 등 기존 launch extras는 target Activity Intent에 직접 넣지 않고,
     * 앱 Receiver가 준비된 뒤 ACTION_COMMAND 호환 방송으로 재전송합니다.
     */
    private fun launchApp(
        context: Context,
        packageName: String,
        activityName: String,
        extras: JSONObject?,
        forceRestart: Boolean
    ): Boolean {
        Log.i(
            TAG,
            "Quest VRShell native launch requested. " +
                    "package=$packageName " +
                    "storedActivity=$activityName " +
                    "incomingForceRestart=$forceRestart"
        )

        return QuestVrShellLauncher.launch(
            context = context,
            packageName = packageName,
            extrasJson = extras?.toString(),
            reason = "HTTP_LAUNCH"
        )
    }

    private fun handleStop(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val packageName = json.optString("packageName", "").trim()

        if (packageName.isBlank()) {
            return error(
                "bad_request",
                "packageName is required",
                400
            )
        }

        val retryCount = maxOf(
            5,
            clampRetryCount(
                json.optInt("retryCount", 3)
            )
        )
        val retryIntervalMs = minOf(
            500,
            clampInterval(
                json.optInt("retryIntervalMs", 1_000)
            )
        )
        val disableUi = json.optBoolean("disableUi", true)
        val goHomeRequested = json.optBoolean("goHome", false)
        val goHomeDelayMs = clampHomeDelay(
            json.optInt("goHomeDelayMs", 0)
        )

        val sequenceGeneration =
            beginStopSequenceBarrier(
                retryCount,
                retryIntervalMs
            )

        /*
         * 대기 중이거나 appCommandLock 진입 전인 모든 launch를 즉시 무효화합니다.
         */
        launchDispatchGeneration.incrementAndGet()
        clearLaunchDedupe(
            "HTTP_STOP"
        )
        QuestVrShellLauncher.cancelPending(
            "HTTP_STOP:$packageName"
        )
        LaunchFocusStabilizer.cancel(
            "HTTP_STOP"
        )
        AgentOperationStore.cancelPendingLaunchForPackage(
            packageName,
            "launch_cancelled_by_stop"
        )
        AppLaunchGuard.clearFor(packageName)

        val operationId = AgentOperationStore.begin(
            AgentOperationStore.TYPE_STOP,
            packageName,
            "HTTP_STOP_RETRY_SEQUENCE"
        )

        /*
         * 레거시 앱의 기존 AAR는 ACTION_STOP만 처리합니다.
         * 전용 stop executor가 바쁜 경우에도 현재 앱은 즉시 종료 명령을 받도록
         * HTTP 처리 스레드에서 targetPackage에 1차 방송을 먼저 보냅니다.
         */
        val immediateBroadcastCalls =
            sendStopToPackage(
                context = context.applicationContext,
                packageName = packageName
            )

        if (disableUi) {
            AutoCapturePauseStore.pauseIndefinitely(
                context,
                "APP_STOP_UI_DISABLED"
            )

            hideAgentUiWithoutLaunchingActivity(
                context,
                "HTTP_STOP:$packageName"
            )
        }

        LastLaunchedAppStore.markClosed(
            context,
            packageName,
            "HTTP_STOP_REQUESTED"
        )

        AgentOperationStore.markDispatched(
            operationId,
            "stop retry sequence queued",
            JSONObject()
                .put("targetPackage", packageName)
                .put("retryCount", retryCount)
                .put("retryIntervalMs", retryIntervalMs)
                .put(
                    "immediateBroadcastCalls",
                    immediateBroadcastCalls
                )
        )

        enqueueStopRetrySequence(
            context = context.applicationContext,
            operationId = operationId,
            sequenceGeneration = sequenceGeneration,
            targetPackage = packageName,
            retryCount = retryCount,
            retryIntervalMs = retryIntervalMs,
            fallbackAllPackages = false,
            priorityPackages = listOf(packageName),
            goHome = !disableUi && goHomeRequested,
            goHomeDelayMs = goHomeDelayMs
        )

        return accepted(
            operationId = operationId,
            extra = JSONObject()
                .put("packageName", packageName)
                .put("retryCount", retryCount)
                .put("retryIntervalMs", retryIntervalMs)
                .put(
                    "immediateBroadcastCalls",
                    immediateBroadcastCalls
                )
                .put("uiDisabled", disableUi)
                .put("goHome", !disableUi && goHomeRequested)
                .put("completionMode", "STOP_RETRY_SEQUENCE")
        )
    }

    private fun handleStopAllStoryWing(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()

        Log.i(
            TAG,
            "handleStopAllStoryWing build=$HANDLER_BUILD_ID"
        )

        /*
         * 연결된 여러 Quest에서 일부 앱만 종료되는 경우를 줄이기 위해
         * 전체 종료는 최소 5회, 최대 500ms 간격으로 보강합니다.
         *
         * 호출자가 3회/1000ms를 보내도 실제 Agent 내부에서는:
         * 0ms / 500ms / 1000ms / 1500ms / 2000ms
         * 다섯 번 전송합니다.
         */
        val retryCount = maxOf(
            STOP_ALL_MIN_RETRY_COUNT,
            clampRetryCount(
                json.optInt("retryCount", 3)
            )
        )
        val retryIntervalMs = minOf(
            STOP_ALL_MAX_RETRY_INTERVAL_MS,
            clampInterval(
                json.optInt("retryIntervalMs", 1_000)
            )
        )
        val disableUi = json.optBoolean("disableUi", true)
        val goHomeRequested = json.optBoolean("goHome", false)
        val goHomeDelayMs = clampHomeDelay(
            json.optInt("goHomeDelayMs", 0)
        )
        val fallbackAllPackages =
            json.optBoolean("fallbackAllPackages", true)

        val priorityPackages =
            parsePackageArray(
                json.optJSONArray("priorityPackages")
            ).toMutableList().apply {
                /*
                 * 코딩/영어 앱은 여러 빌드가 혼재할 수 있으므로 마지막 실행 기록과
                 * 무관하게 0초/1초/2초 모든 회차에 우선 STOP을 보냅니다.
                 */
                PRIORITY_STOP_PACKAGES.forEach { requiredPackage ->
                    if (none {
                            it.equals(
                                requiredPackage,
                                ignoreCase = true
                            )
                        }) {
                        add(requiredPackage)
                    }
                }
            }

        /*
         * fast launch는 HTTP 202 이후 실제 startActivity가 늦게 실행될 수 있습니다.
         * LastLaunchedAppStore에는 큐 등록 시점에 대상 패키지가 저장되므로,
         * 해당 패키지를 0초/1초/2초에 우선 종료합니다.
         */
        val targetPackage = LastLaunchedAppStore
            .get(context)
            ?.packageName
            ?.trim()
            .orEmpty()

        val reservation =
            synchronized(stopAllRequestLock) {
                val now =
                    SystemClock.elapsedRealtime()

                val reusableOperationId =
                    activeStopAllOperationId
                        .takeIf { operationId ->
                            operationId.isNotBlank() &&
                                    now - activeStopAllAcceptedAtElapsedMs <=
                                    STOP_ALL_DEDUPE_WINDOW_MS &&
                                    AgentOperationStore.isPending(operationId)
                        }

                if (!reusableOperationId.isNullOrBlank()) {
                    StopAllReservation(
                        operationId = reusableOperationId,
                        sequenceGeneration = 0L,
                        reused = true
                    )
                } else {
                    val sequenceGeneration =
                        beginStopSequenceBarrier(
                            retryCount,
                            retryIntervalMs
                        )

                    val operationId =
                        AgentOperationStore.begin(
                            AgentOperationStore.TYPE_STOP_ALL,
                            target = targetPackage,
                            reason = "HTTP_STOP_ALL_RETRY_SEQUENCE"
                        )

                    activeStopAllOperationId =
                        operationId

                    activeStopAllAcceptedAtElapsedMs =
                        now

                    StopAllReservation(
                        operationId = operationId,
                        sequenceGeneration = sequenceGeneration,
                        reused = false
                    )
                }
            }

        if (reservation.reused) {
            /*
             * WPF의 응답 유실 재시도는 기존 시퀀스를 취소하지 않고 같은 operation을
             * 재사용합니다. 이전 코드는 retry가 stopSequenceGeneration을 올려
             * 이미 진행 중인 전체 종료를 중간 취소할 수 있었습니다.
             */
            val duplicateTargets =
                linkedSetOf<String>().apply {
                    if (targetPackage.isNotBlank()) {
                        add(targetPackage)
                    }
                    PRIORITY_STOP_PACKAGES.forEach(::add)
                }

            var duplicateBroadcastCalls =
                sendGlobalStopBroadcast(
                    context.applicationContext
                )

            duplicateTargets.forEach { packageName ->
                duplicateBroadcastCalls +=
                    sendStopToPackage(
                        context = context.applicationContext,
                        packageName = packageName
                    )
            }

            /*
             * 중복 stop-all은 네트워크 응답 유실 재시도입니다.
             * STOP 방송은 보강하되 Shield/Agent UI를 다시 전면에 올리지 않습니다.
             *
             * 사용자가 원래 종료 후 새 앱을 실행한 순간 중복 HTTP가 늦게 도착해도
             * 화면 포커스를 다시 가로채지 않게 합니다.
             */
            Log.i(
                TAG,
                "Duplicate stop-all request merged. " +
                        "operationId=${reservation.operationId} target=$targetPackage"
            )

            return accepted(
                operationId = reservation.operationId,
                extra = JSONObject()
                    .put("deduplicated", true)
                    .put("targetPackage", targetPackage)
                    .put("immediateBroadcastCalls", duplicateBroadcastCalls)
                    .put("completionMode", "EXISTING_STOP_RETRY_SEQUENCE")
            )
        }

        val sequenceGeneration =
            reservation.sequenceGeneration

        val operationId =
            reservation.operationId

        /*
         * 아직 실행되지 않은 fastLaunchExecutor 작업과 lock 대기 중 launch를
         * 모두 무효화합니다.
         */
        launchDispatchGeneration.incrementAndGet()
        clearLaunchDedupe(
            "HTTP_STOP_ALL"
        )
        QuestVrShellLauncher.cancelPending(
            "HTTP_STOP_ALL"
        )
        LaunchFocusStabilizer.cancel(
            "HTTP_STOP_ALL"
        )
        AgentOperationStore.cancelPendingLaunches(
            "launch_cancelled_by_stop_all"
        )
        AppLaunchGuard.clear()

        /*
         * HTTP 응답 전에는 실행 중인 동적 Receiver 전체에 global STOP을 보내고,
         * 현재/우선 패키지만 명시적으로 한 번 더 전송합니다.
         * 전체 패키지 명시적 라운드는 전용 executor가 계속 수행합니다.
         *
         * 기존 코드는 LastLaunchedAppStore의 한 패키지에만 즉시 STOP을 보내고,
         * 나머지는 단일 executor의 비동기 라운드를 기다렸습니다.
         * LastLaunched 기록이 틀리거나 executor가 지연되면 일부 앱만 종료될 수 있었습니다.
         */
        val immediateTargets =
            linkedSetOf<String>().apply {
                if (targetPackage.isNotBlank()) {
                    add(targetPackage)
                }

                priorityPackages
                    .filter { it.isNotBlank() }
                    .forEach(::add)
            }

        var immediateBroadcastCalls =
            sendGlobalStopBroadcast(
                context.applicationContext
            )

        immediateTargets.forEach { packageName ->
            immediateBroadcastCalls +=
                sendStopToPackage(
                    context = context.applicationContext,
                    packageName = packageName
                )
        }

        Log.i(
            TAG,
            "STOP immediate global round sent. " +
                    "targetCount=${immediateTargets.size} " +
                    "broadcastCalls=$immediateBroadcastCalls " +
                    "targetPackage=$targetPackage"
        )

        if (disableUi) {
            AutoCapturePauseStore.pauseIndefinitely(
                context,
                "STOP_ALL_UI_DISABLED"
            )

            /*
             * Agent UI task background 처리는 STOP 전송을 늦추지 않도록 Main Looper에 비동기 요청합니다.
             */
            hideAgentUiWithoutLaunchingActivity(
                context,
                "HTTP_STOP_ALL"
            )
        }

        /*
         * 자동 복원에 사용되는 기록은 즉시 제거하되 targetPackage는 위에서 보존했습니다.
         */
        LastLaunchedAppStore.clear(context)

        AgentOperationStore.markDispatched(
            operationId,
            "stop-all retry sequence queued",
            JSONObject()
                .put("targetPackage", targetPackage)
                .put("retryCount", retryCount)
                .put("retryIntervalMs", retryIntervalMs)
                .put("fallbackAllPackages", fallbackAllPackages)
                .put("priorityPackages", priorityPackages)
                .put(
                    "immediateBroadcastCalls",
                    immediateBroadcastCalls
                )
        )

        enqueueStopRetrySequence(
            context = context.applicationContext,
            operationId = operationId,
            sequenceGeneration = sequenceGeneration,
            targetPackage = targetPackage,
            retryCount = retryCount,
            retryIntervalMs = retryIntervalMs,
            fallbackAllPackages = fallbackAllPackages,
            priorityPackages = priorityPackages,
            goHome = !disableUi && goHomeRequested,
            goHomeDelayMs = goHomeDelayMs
        )

        return accepted(
            operationId = operationId,
            extra = JSONObject()
                .put("targetPackage", targetPackage)
                .put("retryCount", retryCount)
                .put("retryIntervalMs", retryIntervalMs)
                .put("fallbackAllPackages", fallbackAllPackages)
                .put("priorityPackages", priorityPackages)
                .put(
                    "immediateBroadcastCalls",
                    immediateBroadcastCalls
                )
                .put("uiDisabled", disableUi)
                .put("goHome", !disableUi && goHomeRequested)
                .put("completionMode", "STOP_RETRY_SEQUENCE")
        )
    }

    /**
     * 새 stop 시퀀스를 시작하고, 시퀀스가 진행되는 동안 늦게 도착한 launch 요청을
     * 일시적으로 거부할 barrier를 설정합니다.
     */
    private fun beginStopSequenceBarrier(
        retryCount: Int,
        retryIntervalMs: Int
    ): Long {
        val sequenceGeneration =
            stopSequenceGeneration.incrementAndGet()

        val retryWindowMs =
            (retryCount - 1L) *
                    retryIntervalMs.toLong()

        stopBarrierUntilElapsedMs.set(
            SystemClock.elapsedRealtime() +
                    retryWindowMs +
                    750L
        )

        return sequenceGeneration
    }

    private fun clearActiveStopAllOperation(
        operationId: String
    ) {
        synchronized(stopAllRequestLock) {
            if (
                activeStopAllOperationId ==
                operationId
            ) {
                activeStopAllOperationId =
                    ""

                activeStopAllAcceptedAtElapsedMs =
                    0L
            }
        }
    }

    /**
     * HTTP 처리 중 현재/우선 패키지에 STOP을 먼저 보내고,
     * 전용 큐가 모든 알려진 패키지에 5회 STOP 라운드를 처리합니다.
     */
    private fun enqueueStopRetrySequence(
        context: Context,
        operationId: String,
        sequenceGeneration: Long,
        targetPackage: String,
        retryCount: Int,
        retryIntervalMs: Int,
        fallbackAllPackages: Boolean,
        priorityPackages: List<String>,
        goHome: Boolean,
        goHomeDelayMs: Int
    ) {
        val expectedBarrierUntil =
            stopBarrierUntilElapsedMs.get()

        stopSequenceExecutor.execute {
            var broadcastCalls = 0

            /*
             * 레거시 10개 앱 호환:
             *
             * 레거시 MultiQuestCommandBridge.aar는 동적 Receiver로
             * ACTION_STOP만 받아 OnStopRequested("")를 호출합니다.
             * APP_STATE/COMMAND_ACK도 없고 ACTION_COMMAND(command=stop)는
             * 기존 C# switch에서 처리하지 않습니다.
             *
             * 따라서 전체 종료에서는 PackageManager 설치 판정 결과에 의존하지 않고
             * WPF/Agent가 알고 있는 모든 StoryWing 패키지에 ACTION_STOP을
             * 매 회차 명시적으로 전송해야 합니다.
             *
             * PackageManager 사전 필터는 패키지 가시성, 패키지명 변형,
             * 오래된 Agent Manifest 때문에 실제 설치 앱을 누락시킬 수 있으므로
             * STOP 대상 필터로 사용하지 않습니다.
             */
            val legacyFallbackPackages =
                if (fallbackAllPackages) {
                    STORYWING_PACKAGES
                        .filter { it.isNotBlank() }
                        .distinct()
                } else {
                    emptyList()
                }

            try {
                repeat(retryCount) { retryIndex ->
                    /*
                     * 각 재시도 직전에도 generation을 올려, STOP 사이에 뒤늦게
                     * 진입한 launch 작업을 다시 무효화합니다.
                     */
                    launchDispatchGeneration.incrementAndGet()
                    LaunchFocusStabilizer.cancel(
                        "stop_retry_${retryIndex + 1}"
                    )

                    /*
                     * STOP 방송은 appCommandLock을 기다리지 않습니다.
                     * 느린 startActivity가 lock을 오래 잡고 있어도 1차 STOP을 즉시 보내고,
                     * 이후 1초/2초 재시도가 늦게 열린 Activity를 다시 잡습니다.
                     */
                    val roundTargets =
                        linkedSetOf<String>()

                    if (targetPackage.isNotBlank()) {
                        roundTargets.add(
                            targetPackage
                        )
                    }

                    priorityPackages
                        .filter { it.isNotBlank() }
                        .forEach(
                            roundTargets::add
                        )

                    if (fallbackAllPackages) {
                        legacyFallbackPackages
                            .forEach(
                                roundTargets::add
                            )
                    }

                    if (roundTargets.isEmpty()) {
                        /*
                         * 패키지 조회 결과가 비어 있는 예외 상황에서도
                         * 알려진 전체 목록으로 STOP을 보냅니다.
                         */
                        roundTargets.addAll(
                            STORYWING_PACKAGES
                        )
                    }

                    Log.i(
                        TAG,
                        "STOP retry round. " +
                                "round=${retryIndex + 1}/$retryCount " +
                                "target=$targetPackage " +
                                "roundTargetCount=${roundTargets.size} " +
                                "legacyFallbackCount=" +
                                legacyFallbackPackages.size
                    )

                    for (packageName in roundTargets) {
                        broadcastCalls +=
                            sendStopToPackage(
                                context = context,
                                packageName = packageName
                            )
                    }

                    if (retryIndex < retryCount - 1) {
                        SystemClock.sleep(
                            retryIntervalMs.toLong()
                        )
                    }
                }

                if (goHome) {
                    if (goHomeDelayMs > 0) {
                        SystemClock.sleep(
                            goHomeDelayMs.toLong()
                        )
                    }

                    appCommandLock.withLock {
                        goHome(context)
                    }
                }

                AgentOperationStore.complete(
                    operationId,
                    "stop retry sequence sent; " +
                            "APP_STATE_CLOSED acknowledgement not required",
                    JSONObject()
                        .put("targetPackage", targetPackage)
                        .put("retryCount", retryCount)
                        .put("retryIntervalMs", retryIntervalMs)
                        .put("broadcastCalls", broadcastCalls)
                        .put("fallbackAllPackages", fallbackAllPackages)
                        .put(
                            "legacyFallbackPackageCount",
                            legacyFallbackPackages.size
                        )
                        .put("priorityPackages", priorityPackages)
                )

                clearActiveStopAllOperation(
                    operationId
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "stop retry sequence failed. " +
                            "target=$targetPackage",
                    e
                )

                AgentOperationStore.fail(
                    operationId,
                    e.message ?: "stop_retry_sequence_failed",
                    retryable = true
                )

                clearActiveStopAllOperation(
                    operationId
                )
            } finally {
                if (
                    sequenceGeneration ==
                    stopSequenceGeneration.get()
                ) {
                    stopBarrierUntilElapsedMs.compareAndSet(
                        expectedBarrierUntil,
                        0L
                    )
                }
            }
        }
    }

    /**
     * 실행 중인 모든 동적 StoryWing Receiver에 STOP을 한 번에 전달합니다.
     * 패키지 목록 누락과 HTTP 응답 지연을 줄이는 빠른 1차 경로입니다.
     */
    private fun sendGlobalStopBroadcast(
        context: Context
    ): Int {
        val payload = JSONObject()
            .put("command", "stop")
            .put("reason", "multiquest_remote_stop_all")
            .put("timestamp", System.currentTimeMillis())
            .toString()

        var sentCount = 0

        for (
        action in arrayOf(
            ACTION_STORYWING_STOP,
            ACTION_STORYWING_COMMAND
        )
        ) {
            try {
                val intent = Intent(action).apply {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("command", "stop")
                    putExtra("action", "stop")
                    putExtra("reason", "multiquest_remote_stop_all")
                    putExtra("payload", payload)
                }

                context.sendBroadcast(intent)
                sentCount++
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "sendGlobalStopBroadcast failed. action=$action",
                    e
                )
            }
        }

        Log.i(
            TAG,
            "Global STOP broadcasts sent. count=$sentCount"
        )

        return sentCount
    }

    /**
     * 지정 패키지에 ACTION_STOP과 ACTION_COMMAND(command=stop)를 모두 보냅니다.
     * ACTION_STOP은 레거시 AAR, ACTION_COMMAND는 신형 bridge 호환 경로입니다.
     */
    private fun sendStopToPackage(
        context: Context,
        packageName: String
    ): Int {
        if (packageName.isBlank()) return 0

        val payload = JSONObject()
            .put("command", "stop")
            .put("reason", "multiquest_remote_stop")
            .put("timestamp", System.currentTimeMillis())
            .toString()

        var sentCount = 0

        val actions = arrayOf(
            ACTION_STORYWING_STOP,
            ACTION_STORYWING_COMMAND
        )

        for (action in actions) {
            try {
                val intent = Intent(action).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

                    putExtra("command", "stop")
                    putExtra("action", "stop")
                    putExtra("reason", "multiquest_remote_stop")
                    putExtra("payload", payload)
                }

                context.sendBroadcast(intent)
                sentCount++
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "sendStopToPackage failed. " +
                            "package=$packageName action=$action",
                    e
                )
            }
        }

        Log.i(
            TAG,
            "STOP compatibility broadcasts sent. " +
                    "package=$packageName count=$sentCount"
        )

        return sentCount
    }

    private fun parsePackageArray(
        array: org.json.JSONArray?
    ): List<String> {
        if (array == null) return emptyList()

        val out = linkedSetOf<String>()

        for (index in 0 until array.length()) {
            val packageName = array
                .optString(index, "")
                .trim()

            if (packageName.isNotBlank()) {
                out.add(packageName)
            }
        }

        return out.toList()
    }

    /**
     * STOP/STOP_ALL의 사용자 체감 종료를 즉시 보장합니다.
     *
     * Receiver/AAR가 없는 앱은 일반 권한 Agent가 프로세스를 강제 종료할 수 없으므로
     * Shield를 전면에 올려 앱 task를 background로 보내는 것이 비특권 환경의
     * 유일한 결정적 fallback입니다. Shield는 다음 StoryWing launcher Intent 뒤에
     * 그대로 남고 launch 경로에서 finish하지 않습니다.
     */
    /**
     * STOP/STOP_ALL의 사용자 체감 종료를 즉시 보장합니다.
     *
     * Receiver/AAR가 없는 앱은 일반 권한 Agent가 프로세스를 강제 종료할 수 없으므로
     * Shield를 전면에 올려 앱 task를 background로 보내는 것이 비특권 환경의
     * 결정적 fallback입니다.
     *
     * Agent UI/Capture task를 먼저 정리하고, 마지막 작업으로 CLEAR_TASK Shield를
     * 시작합니다. Shield가 올라온 뒤 finishAndRemoveTask를 호출해 Shield까지 지우는
     * 이전 순서의 task 경쟁을 방지합니다.
     */
    /**
     * STOP/STOP_ALL의 사용자 체감 종료를 보장합니다.
     *
     * Receiver/AAR가 없는 앱은 일반 권한 Agent가 프로세스를 강제 종료할 수 없으므로
     * Shield를 전면에 올려 앱 task를 background로 보내는 것이 비특권 환경의
     * 결정적 fallback입니다.
     *
     * HTTP 응답을 Activity 전환 때문에 지연시키지 않도록 Main Looper에 즉시 예약합니다.
     * 같은 Runnable 안에서 Agent 권한/UI task를 먼저 정리하고 마지막으로 CLEAR_TASK
     * Shield를 시작합니다.
     */
    /**
     * STOP/STOP_ALL의 사용자 체감 종료를 보장합니다.
     *
     * 새 launch가 이 Runnable보다 나중에 접수되면 generation이 달라지므로
     * Shield를 시작하지 않습니다. 이미 표시된 Shield는 external launch의
     * Main Looper start 직전에 동기적으로 종료됩니다.
     */
    /**
     * STOP/STOP_ALL에서 Agent UI를 숨기되 새 Activity는 절대 띄우지 않습니다.
     *
     * 이전 IdleShieldActivity 방식은 Quest가 이를 일반 2D panel/volumetric window로
     * 라우팅하면서 immersive 앱 전환과 겹칠 수 있었습니다.
     *
     * 새 정책:
     * - CapturePermissionActivity 종료
     * - KeepAwakeActivity 종료
     * - 기존 IdleShield가 남아 있으면 plain finish
     * - MainActivity는 finish하지 않고 task만 background
     *
     * MainActivity 인스턴스를 유지해야 다음 WPF launch가
     * VRShell native Intent를 실제 Activity source에서 보낼 수 있습니다.
     */
    private fun hideAgentUiWithoutLaunchingActivity(
        context: Context,
        reason: String
    ) {
        val expectedUiGeneration =
            agentUiTransitionGeneration
                .incrementAndGet()

        mainHandler.post {
            if (
                expectedUiGeneration !=
                agentUiTransitionGeneration.get()
            ) {
                Log.i(
                    TAG,
                    "Stale Agent UI hide request suppressed. " +
                            "reason=$reason " +
                            "expectedGeneration=$expectedUiGeneration " +
                            "currentGeneration=" +
                            agentUiTransitionGeneration.get()
                )

                return@post
            }

            CapturePermissionActivity
                .finishCurrentForUiDisable()

            KeepAwakeActivity
                .finishCurrent()

            IdleShieldActivity
                .finishCurrentForExternalLaunchNow()

            val mainTaskBackgrounded =
                MainActivity
                    .moveTaskToBackForRemoteStopNow(
                        reason
                    )

            AgentUiState.isIdleShieldVisible =
                false

            Log.i(
                TAG,
                "Agent UI hidden without launching Activity. " +
                        "reason=$reason " +
                        "mainTaskBackgrounded=$mainTaskBackgrounded " +
                        "uiGeneration=$expectedUiGeneration"
            )
        }
    }

    private fun handleHome(context: Context): AgentCommandResult {
        val ok = appCommandLock.withLock {
            goHome(context)
        }

        return if (ok) {
            completed()
        } else {
            error("home_failed", "Could not open Home", 500)
        }
    }

    private fun goHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runActivityStartOnMain(context, intent)
    }

    private fun canOpenCaptureUi(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive && !(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            pm.isDeviceIdleMode
                    )
        } catch (_: Exception) {
            false
        }
    }

    private fun handleRestartCapture(
        context: Context
    ): AgentCommandResult {
        if (!canOpenCaptureUi(context)) {
            return retryableError(
                code = "device_asleep",
                message = "Device is not interactive",
                retryAfterMs = 5_000
            )
        }

        val operationId = AgentService.requestCaptureRestart(
            context,
            "HTTP_RESTART_CAPTURE"
        )

        return accepted(operationId)
    }

    private fun handleStartCaptureUi(
        context: Context
    ): AgentCommandResult {
        if (!canOpenCaptureUi(context)) {
            return retryableError(
                code = "device_asleep",
                message = "Device is not interactive",
                retryAfterMs = 5_000
            )
        }

        val snapshot = CaptureStateStore.snapshot()

        if (
            CaptureService.isServiceAlive &&
            snapshot.state == CaptureStateStore.STATE_RUNNING &&
            snapshot.encodedFrameCount > 0L &&
            snapshot.frameAgeMs in 0..20_000L
        ) {
            return completed(
                JSONObject()
                    .put("alreadyRunning", true)
                    .put("streamGeneration", snapshot.streamGeneration)
            )
        }

        if (
            (snapshot.state == CaptureStateStore.STATE_WAITING_PERMISSION ||
                    snapshot.state == CaptureStateStore.STATE_STARTING) &&
            snapshot.stateDurationMs < ACTIVE_CAPTURE_TRANSITION_TIMEOUT_MS
        ) {
            val activeOperationId = snapshot.activeOperationId.ifBlank {
                AgentOperationStore.latestPendingCaptureOperationId()
            }

            if (activeOperationId.isBlank()) {
                return retryableError(
                    code = "capture_transition_without_operation",
                    message = "Capture is already transitioning but has no operationId",
                    retryAfterMs = 2_000
                )
            }

            return AgentCommandResult(
                202,
                JSONObject()
                    .put("ok", true)
                    .put("accepted", true)
                    .put("completed", false)
                    .put("alreadyInProgress", true)
                    .put("operationId", activeOperationId)
                    .put("state", snapshot.state)
            )
        }

        val operationId = AgentService.requestCaptureStart(
            context,
            "HTTP_START_CAPTURE_UI"
        )

        return accepted(operationId)
    }

    private fun handleWake(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val keepAwake = json.optBoolean("keepAwake", true)

        if (keepAwake) {
            KeepAliveController.acquire(context)
        }

        val interactive = canOpenCaptureUi(context)
        if (interactive) {
            return completed(
                JSONObject()
                    .put("activityShown", false)
                    .put("wakeLockPulsed", false)
                    .put("alreadyInteractive", true)
                    .put("keepAliveEnabled", KeepAliveController.isEnabled)
            )
        }

        val operationId = AgentOperationStore.begin(
            AgentOperationStore.TYPE_WAKE,
            reason = "HTTP_WAKE"
        )

        // 일반 앱의 백그라운드 Activity 시작이 차단되는 경우를 대비한
        // Quest 전용 보조 경로입니다. 3초 타임아웃으로 자동 해제됩니다.
        val wakeLockPulsed = pulseScreenWakeLock(context)
        val shown = openKeepAwakeActivity(
            context,
            "HTTP_WAKE",
            operationId
        )

        if (!shown && !wakeLockPulsed) {
            AgentOperationStore.fail(
                operationId,
                "wake_dispatch_failed",
                retryable = true
            )
            return retryableError(
                "wake_dispatch_failed",
                "Could not dispatch a wake mechanism",
                2_000,
                operationId
            )
        }

        AgentOperationStore.markDispatched(
            operationId,
            "wake dispatched; activity=$shown wakeLock=$wakeLockPulsed"
        )
        scheduleWakeVerification(operationId, context.applicationContext)

        return accepted(
            operationId,
            JSONObject()
                .put("activityShown", shown)
                .put("wakeLockPulsed", wakeLockPulsed)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
        )
    }

    private fun handleKeepAwake(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()
        val enabled = json.optBoolean("enabled", true)

        if (enabled) {
            KeepAliveController.acquire(context)
        } else {
            KeepAwakeActivity.finishCurrent()
            KeepAliveController.release()
        }

        return completed(
            JSONObject()
                .put("enabled", enabled)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
                .put("cpuWakeLockHeld", KeepAliveController.isCpuWakeLockHeld)
                .put("wifiLockHeld", KeepAliveController.isWifiLockHeld)
        )
    }

    private fun handleShowKeepAwake(
        context: Context
    ): AgentCommandResult {
        KeepAliveController.acquire(context)

        val operationId = AgentOperationStore.begin(
            AgentOperationStore.TYPE_WAKE,
            reason = "HTTP_SHOW_KEEP_AWAKE"
        )

        val shown = openKeepAwakeActivity(
            context,
            "HTTP_SHOW_KEEP_AWAKE",
            operationId
        )

        if (!shown) {
            AgentOperationStore.fail(
                operationId,
                "keep_awake_activity_start_failed",
                retryable = true
            )
            return retryableError(
                "keep_awake_activity_start_failed",
                "Could not dispatch KeepAwakeActivity",
                2_000,
                operationId
            )
        }

        AgentOperationStore.markDispatched(
            operationId,
            "KeepAwakeActivity dispatched"
        )

        return accepted(
            operationId,
            JSONObject()
                .put("activityShown", true)
                .put("keepAliveEnabled", KeepAliveController.isEnabled)
        )
    }

    private fun handleHideKeepAwake(): AgentCommandResult {
        KeepAwakeActivity.finishCurrent()
        return completed(JSONObject().put("hidden", true))
    }

    private fun openKeepAwakeActivity(
        context: Context,
        reason: String,
        operationId: String
    ): Boolean {
        // KeepAwakeActivity에 createIntent()가 없어도 컴파일되도록
        // 여기에서 Intent를 직접 생성합니다.
        val intent = Intent(context, KeepAwakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

            putExtra("reason", reason)
            putExtra("operationId", operationId)
        }

        return runActivityStartOnMain(context, intent)
    }

    private fun handleAppCommand(
        context: Context,
        body: String
    ): AgentCommandResult {
        val json = parseBody(body) ?: return invalidJson()

        val packageName = json.optString("packageName", "").trim()
        val command = json.optString("command", "").trim()
        val payload = json.optJSONObject("payload")
            ?: json.optJSONObject("args")
            ?: JSONObject()

        val requestId = json
            .optString("requestId", "")
            .trim()
            .take(MAX_REQUEST_ID_LENGTH)

        val retryCount = clampRetryCount(
            json.optInt(
                "retryCount",
                APP_COMMAND_DEFAULT_RETRY_COUNT
            )
        )

        val retryIntervalMs = clampInterval(
            json.optInt(
                "retryIntervalMs",
                APP_COMMAND_DEFAULT_RETRY_INTERVAL_MS
            )
        )

        Log.i(
            TAG,
            "handleAppCommand. " +
                    "agentApplicationId=${context.packageName} " +
                    "targetPackage=$packageName command=$command " +
                    "requestId=$requestId retryCount=$retryCount " +
                    "retryIntervalMs=$retryIntervalMs payload=$payload"
        )

        if (packageName.isBlank()) {
            return error(
                "bad_request",
                "packageName is required",
                400
            )
        }

        if (command.isBlank()) {
            return error(
                "bad_request",
                "command is required",
                400
            )
        }

        if (!isPackageInstalled(context, packageName)) {
            return error(
                code = "package_not_installed",
                message = "Package is not installed: $packageName",
                httpCode = 404,
                retryable = false
            )
        }

        findExistingAppCommandOperation(requestId)?.let { existingOperationId ->
            return accepted(
                operationId = existingOperationId,
                extra = JSONObject()
                    .put("packageName", packageName)
                    .put("command", command)
                    .put("requestId", requestId)
                    .put("duplicate", true)
            )
        }

        val operationId = AgentOperationStore.begin(
            type = "appCommand",
            target = packageName,
            reason = command
        )

        rememberAppCommandRequest(
            requestId = requestId,
            operationId = operationId
        )

        AgentOperationStore.markDispatched(
            operationId = operationId,
            message = "app command retry sequence queued",
            details = JSONObject()
                .put("packageName", packageName)
                .put("command", command)
                .put("requestId", requestId)
                .put("retryCount", retryCount)
                .put("retryIntervalMs", retryIntervalMs)
        )

        scheduleAppCommandBroadcasts(
            context = context.applicationContext,
            operationId = operationId,
            requestId = requestId,
            packageName = packageName,
            command = command,
            payload = JSONObject(payload.toString()),
            retryCount = retryCount,
            retryIntervalMs = retryIntervalMs
        )

        return accepted(
            operationId = operationId,
            extra = JSONObject()
                .put("packageName", packageName)
                .put("command", command)
                .put("requestId", requestId)
                .put("retryCount", retryCount)
                .put("retryIntervalMs", retryIntervalMs)
                .put("verification", "COMMAND_ACK")
        )
    }

    private fun scheduleAppCommandBroadcasts(
        context: Context,
        operationId: String,
        requestId: String,
        packageName: String,
        command: String,
        payload: JSONObject,
        retryCount: Int,
        retryIntervalMs: Int
    ) {
        /*
         * attempt 1은 scheduler에 맡기지 않고 현재 HTTP 처리 흐름에서 즉시 전송합니다.
         * 현장 로그에서 /command/appCommand는 수신됐지만 scheduler 전송 로그가 없던
         * 경우에도 최소 한 번은 반드시 broadcast가 나가도록 보장합니다.
         */
        val firstSent = sendAppCommandBroadcast(
            context = context,
            packageName = packageName,
            command = command,
            payload = payload,
            operationId = operationId,
            requestId = requestId,
            attemptIndex = 0,
            retryCount = retryCount
        )

        Log.i(
            TAG,
            "App command broadcast immediate. " +
                    "package=$packageName command=$command " +
                    "attempt=1/$retryCount sent=$firstSent " +
                    "operationId=$operationId requestId=$requestId"
        )

        if (retryCount <= 1) {
            scheduleAppCommandAckTimeout(
                operationId = operationId,
                packageName = packageName,
                command = command,
                requestId = requestId,
                retryCount = retryCount,
                delayMs = APP_COMMAND_ACK_GRACE_MS
            )
            return
        }

        for (attemptIndex in 1 until retryCount) {
            val delayMs =
                attemptIndex.toLong() *
                        retryIntervalMs.toLong()

            appCommandScheduler.schedule(
                Runnable {
                    if (!AgentOperationStore.isPending(operationId)) {
                        return@Runnable
                    }

                    val sent = sendAppCommandBroadcast(
                        context = context,
                        packageName = packageName,
                        command = command,
                        payload = payload,
                        operationId = operationId,
                        requestId = requestId,
                        attemptIndex = attemptIndex,
                        retryCount = retryCount
                    )

                    Log.i(
                        TAG,
                        "App command broadcast retry. " +
                                "package=$packageName command=$command " +
                                "attempt=${attemptIndex + 1}/$retryCount sent=$sent " +
                                "operationId=$operationId requestId=$requestId"
                    )

                    if (attemptIndex == retryCount - 1) {
                        scheduleAppCommandAckTimeout(
                            operationId = operationId,
                            packageName = packageName,
                            command = command,
                            requestId = requestId,
                            retryCount = retryCount,
                            delayMs = APP_COMMAND_ACK_GRACE_MS
                        )
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun scheduleAppCommandAckTimeout(
        operationId: String,
        packageName: String,
        command: String,
        requestId: String,
        retryCount: Int,
        delayMs: Long
    ) {
        appCommandScheduler.schedule(
            Runnable {
                if (AgentOperationStore.isPending(operationId)) {
                    AgentOperationStore.fail(
                        operationId = operationId,
                        message = "app_command_ack_timeout",
                        retryable = true,
                        details = JSONObject()
                            .put("packageName", packageName)
                            .put("command", command)
                            .put("requestId", requestId)
                            .put("retryCount", retryCount)
                    )

                    Log.w(
                        TAG,
                        "App command ACK timeout. " +
                                "package=$packageName command=$command " +
                                "operationId=$operationId requestId=$requestId"
                    )
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sendAppCommandBroadcast(
        context: Context,
        packageName: String,
        command: String,
        payload: JSONObject,
        operationId: String,
        requestId: String,
        attemptIndex: Int,
        retryCount: Int
    ): Boolean {
        return try {
            /*
             * 현장 영어/코딩 앱의 기존 MultiQuestCommandBridge.aar는
             * ACTION_COMMAND에서 Intent의 payload 문자열만 Unity에 전달하고,
             * command / operationId / requestId top-level extras는 버립니다.
             *
             * 따라서 payload 문자열 자체를 canonical envelope로 만들어야
             * 기존 AAR를 재빌드하지 않아도 Unity가 명령 종류와 ACK 정보를
             * 모두 복구할 수 있습니다.
             */
            val bridgeEnvelope = JSONObject()
                .put("requestId", requestId)
                .put("operationId", operationId)
                .put("command", command)
                .put("args", JSONObject(payload.toString()))
                .put("source", "agent_app_command")
                .put("timestamp", System.currentTimeMillis())
                .put("attempt", attemptIndex + 1)
                .put("retryCount", retryCount)

            val intent = Intent(ACTION_STORYWING_COMMAND).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

                // 신형/직접 Receiver용 top-level extras
                putExtra("command", command)
                putExtra("action", command)
                putExtra("operationId", operationId)
                putExtra("requestId", requestId)
                putExtra("attempt", attemptIndex + 1)
                putExtra("retryCount", retryCount)
                putExtra("issuedAtMs", System.currentTimeMillis())

                // 기존 AAR가 Unity에 넘기는 핵심 문자열
                putExtra("payload", bridgeEnvelope.toString())

                // 디버깅 및 구형 raw payload 소비자용
                putExtra("rawPayload", payload.toString())

                // getIntExtra/getStringExtra 기반 구형 Unity 코드도 지원
                putJsonExtras(this, payload)
            }

            context.sendBroadcast(intent)

            Log.i(
                TAG,
                "App command broadcast sent. " +
                        "package=$packageName command=$command " +
                        "attempt=${attemptIndex + 1}/$retryCount " +
                        "operationId=$operationId requestId=$requestId " +
                        "bridgePayload=$bridgeEnvelope"
            )

            true
        } catch (e: Exception) {
            Log.e(
                TAG,
                "sendAppCommandBroadcast failed. " +
                        "package=$packageName command=$command",
                e
            )
            false
        }
    }

    private fun findExistingAppCommandOperation(
        requestId: String
    ): String? {
        if (requestId.isBlank()) return null

        val now = SystemClock.elapsedRealtime()

        synchronized(appCommandRequestLock) {
            pruneAppCommandRequestsLocked(now)

            val entry = appCommandRequests[requestId]
                ?: return null

            return if (
                entry.expiresAtElapsedMs > now &&
                AgentOperationStore.getJson(entry.operationId) != null
            ) {
                entry.operationId
            } else {
                appCommandRequests.remove(requestId)
                null
            }
        }
    }

    private fun rememberAppCommandRequest(
        requestId: String,
        operationId: String
    ) {
        if (requestId.isBlank()) return

        val now = SystemClock.elapsedRealtime()

        synchronized(appCommandRequestLock) {
            pruneAppCommandRequestsLocked(now)

            appCommandRequests[requestId] = AppCommandRequestEntry(
                operationId = operationId,
                expiresAtElapsedMs = now + APP_COMMAND_REQUEST_TTL_MS
            )

            while (appCommandRequests.size > 100) {
                val oldestKey = appCommandRequests.keys.firstOrNull()
                    ?: break
                appCommandRequests.remove(oldestKey)
            }
        }
    }

    private fun pruneAppCommandRequestsLocked(now: Long) {
        val iterator = appCommandRequests.entries.iterator()

        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtElapsedMs <= now) {
                iterator.remove()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun pulseScreenWakeLock(context: Context): Boolean {
        return try {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.isInteractive) return true

            val wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "MultiQuest:TransientScreenWake"
            )
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(TRANSIENT_SCREEN_WAKE_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Transient screen wake lock failed", e)
            false
        }
    }

    private fun scheduleWakeVerification(
        operationId: String,
        context: Context
    ) {
        mainHandler.postDelayed({
            if (!AgentOperationStore.isPending(operationId)) {
                return@postDelayed
            }

            val power = try {
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            } catch (_: Exception) {
                null
            }

            if (power?.isInteractive == true) {
                AgentOperationStore.complete(
                    operationId,
                    "device_interactive_after_wake_dispatch"
                )
            } else {
                AgentOperationStore.fail(
                    operationId,
                    "device_still_not_interactive",
                    retryable = true
                )
            }
        }, WAKE_VERIFY_DELAY_MS)
    }

    private fun scheduleLaunchVerification(
        context: Context,
        operationId: String,
        packageName: String
    ) {
        mainHandler.postDelayed({
            if (AgentOperationStore.isPending(operationId)) {
                AppLaunchGuard.clearFor(packageName)
                AgentOperationStore.fail(
                    operationId,
                    "app_active_ack_timeout",
                    retryable = true,
                    details = JSONObject()
                        .put("packageName", packageName)
                        .put("expectedAck", "APP_STATE_ACTIVE")
                        .put("timeoutMs", APP_LAUNCH_VERIFY_TIMEOUT_MS)
                )
                LastLaunchedAppStore.markClosed(
                    context = context,
                    packageName = packageName,
                    reason = "APP_ACTIVE_ACK_TIMEOUT"
                )
            }
        }, APP_LAUNCH_VERIFY_TIMEOUT_MS)
    }

    /**
     * 외부 StoryWing launcher Intent를 Main Looper에서 한 번만 실행합니다.
     * target launch 전후에 IdleShield/MainActivity task를 일괄 finish하지 않습니다.
     */
    /**
     * 대상 Activity를 Main Looper에서 Context.startActivity()로 직접 한 번만 실행합니다.
     *
     * Android 14에서 PendingIntent를 이용한 background launch 우회는 사용하지 않습니다.
     * Quest에서는 Activity 프로세스만 시작되고 VR compositor hand-off가 끝나지 않는
     * 오디오-only 로딩 회귀를 만들 수 있었기 때문입니다.
     */
    private fun runActivityStartOnMain(
        context: Context,
        intent: Intent,
        timeoutMs: Long = ACTIVITY_DISPATCH_TIMEOUT_MS,
        closeIdleShieldBeforeStart: Boolean = false,
        preferMainActivitySource: Boolean = false
    ): Boolean {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            if (closeIdleShieldBeforeStart) {
                /*
                 * 현재 Main Looper이므로 Shield plain finish가 즉시 실행됩니다.
                 * finish 후 대기하거나 task를 제거하지 않고 바로 XR Activity를 시작합니다.
                 */
                IdleShieldActivity
                    .finishCurrentForExternalLaunchNow()
            }

            return startActivityDirect(
                context = context,
                intent = intent,
                preferMainActivitySource =
                    preferMainActivitySource
            )
        }

        val result =
            AtomicBoolean(false)

        val latch =
            CountDownLatch(1)

        mainHandler.post {
            try {
                if (closeIdleShieldBeforeStart) {
                    /*
                     * 같은 Main Looper 작업 안에서 Shield를 먼저 닫고
                     * 곧바로 대상 XR Activity를 시작합니다.
                     */
                    IdleShieldActivity
                        .finishCurrentForExternalLaunchNow()
                }

                result.set(
                    startActivityDirect(
                        context = context,
                        intent = intent,
                        preferMainActivitySource =
                            preferMainActivitySource
                    )
                )
            } finally {
                latch.countDown()
            }
        }

        return try {
            latch.await(
                timeoutMs,
                TimeUnit.MILLISECONDS
            ) &&
                    result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread()
                .interrupt()

            false
        }
    }

    private fun startActivityDirect(
        context: Context,
        intent: Intent,
        preferMainActivitySource: Boolean = false
    ): Boolean {
        if (preferMainActivitySource) {
            val activityLaunchOk =
                MainActivity
                    .launchExternalActivityNow(
                        intent
                    )

            if (activityLaunchOk) {
                Log.i(
                    TAG,
                    "External launch route selected. " +
                            "route=MAIN_ACTIVITY_SOURCE " +
                            "component=${intent.component}"
                )

                return true
            }

            Log.w(
                TAG,
                "MainActivity source unavailable; " +
                        "application-context fallback. " +
                        "component=${intent.component}"
            )
        }

        val applicationIntent =
            Intent(
                intent
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        return try {
            context.applicationContext
                .startActivity(
                    applicationIntent
                )

            Log.i(
                TAG,
                "Context.startActivity dispatched. " +
                        "route=APPLICATION_CONTEXT_FALLBACK " +
                        "component=${applicationIntent.component} " +
                        "action=${applicationIntent.action} " +
                        "flags=0x${applicationIntent.flags.toString(16)}"
            )

            true
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Direct startActivity failed. " +
                        "component=${applicationIntent.component} " +
                        "action=${applicationIntent.action} " +
                        "flags=0x${applicationIntent.flags.toString(16)}",
                e
            )

            false
        }
    }

    private fun clearLaunchDedupe(
        reason: String
    ) {
        synchronized(
            launchRequestLock
        ) {
            lastLaunchPackageName =
                ""

            lastLaunchAcceptedAtElapsedMs =
                0L

            lastLaunchOperationId =
                ""
        }

        Log.d(
            TAG,
            "Launch dedupe cleared. reason=$reason"
        )
    }

    private fun parseBody(body: String): JSONObject? {
        if (body.isBlank()) return JSONObject()
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun putJsonExtras(
        intent: Intent,
        extras: JSONObject?
    ) {
        if (extras == null) return

        val keys = extras.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = extras.opt(key)) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> {
                    val asLong = value.toLong()
                    if (value == asLong.toDouble()) {
                        intent.putExtra(key, asLong)
                    } else {
                        intent.putExtra(key, value)
                    }
                }
                else -> intent.putExtra(key, value?.toString().orEmpty())
            }
        }
    }

    private fun clampRetryCount(value: Int): Int =
        value.coerceIn(1, MAX_RETRY_COUNT)

    private fun clampInterval(value: Int): Int =
        value.coerceIn(50, MAX_RETRY_INTERVAL_MS)

    private fun clampHomeDelay(value: Int): Int =
        value.coerceIn(0, MAX_HOME_DELAY_MS)

    private fun invalidJson(): AgentCommandResult =
        error("invalid_json", "Request body is not valid JSON", 400)

    private fun accepted(
        operationId: String,
        extra: JSONObject = JSONObject()
    ): AgentCommandResult {
        val body = JSONObject()
            .put("ok", true)
            .put("accepted", true)
            .put("completed", false)
            .put("retryable", false)
            .put("operationId", operationId)

        merge(body, extra)
        return AgentCommandResult(202, body)
    }

    private fun completed(
        extra: JSONObject = JSONObject()
    ): AgentCommandResult {
        val body = JSONObject()
            .put("ok", true)
            .put("accepted", true)
            .put("completed", true)
            .put("retryable", false)

        merge(body, extra)
        return AgentCommandResult(200, body)
    }

    private fun retryableError(
        code: String,
        message: String,
        retryAfterMs: Int,
        operationId: String = ""
    ): AgentCommandResult {
        return AgentCommandResult(
            409,
            JSONObject()
                .put("ok", false)
                .put("accepted", false)
                .put("completed", false)
                .put("retryable", true)
                .put("retryAfterMs", retryAfterMs)
                .put("operationId", operationId)
                .put("error", code)
                .put("message", message)
        )
    }

    private fun error(
        code: String,
        message: String,
        httpCode: Int,
        operationId: String = "",
        retryable: Boolean = false
    ): AgentCommandResult {
        return AgentCommandResult(
            httpCode,
            JSONObject()
                .put("ok", false)
                .put("accepted", false)
                .put("completed", false)
                .put("retryable", retryable)
                .put("operationId", operationId)
                .put("error", code)
                .put("message", message)
        )
    }

    private fun merge(
        destination: JSONObject,
        source: JSONObject
    ) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            destination.put(key, source.opt(key))
        }
    }
}