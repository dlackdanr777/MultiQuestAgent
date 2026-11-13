package tdg.oculuswirelessadb.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import tdg.oculuswirelessadb.IdleShieldActivity
import tdg.oculuswirelessadb.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Meta Quest immersive 앱 실행을 VRShell의 제어 BroadcastReceiver에 위임합니다.
 *
 * R9 문제:
 * - Agent MainActivity.startActivity(VRShell MainActivity)로 실행
 * - Agent 2D panel task를 계속 유지
 * - 대상 앱은 FOCUSED/Fully drawn/72FPS인데 launchId가 비어 있음
 * - VRShell APP_START 전환이 종료되지 않고 점 세 개 화면이 유지됨
 *
 * R10 정책:
 * 1. ShellControlBroadcastReceiver에 ACTION_LAUNCH를 명시적으로 방송
 * 2. VRShell NavigationRouter가 immersive launch 전환을 직접 생성
 * 3. 방송 직후 Agent MainActivity task를 finishAndRemoveTask
 * 4. 대상 Unity Activity 직접 실행, PendingIntent, applicationContext Activity 실행 금지
 * 5. Unity 프로젝트와 AAR는 수정하지 않음
 */
object QuestVrShellLauncher {

    private const val TAG =
        "MQ-VRShellLaunch"

    private const val VRSHELL_PACKAGE =
        "com.oculus.vrshell"

    private const val VRSHELL_CONTROL_RECEIVER =
        "com.oculus.vrshell.ShellControlBroadcastReceiver"

    private const val ACTION_VRSHELL_LAUNCH =
        "com.oculus.vrshell.intent.action.LAUNCH"

    private const val EXTRA_INTENT_SOURCE_PACKAGE =
        "intent_pkg"

    private const val ACTION_STORYWING_COMMAND =
        "com.storywing.multiquest.ACTION_COMMAND"

    private const val MAIN_DISPATCH_TIMEOUT_MS =
        5_000L

    /**
     * Unity/AAR Receiver 등록 시점 차이를 흡수하기 위한 호환 방송 시점입니다.
     */
    private val EXTRA_DELAYS_MS =
        longArrayOf(
            650L,
            1_250L,
            2_200L,
            3_600L,
            5_500L
        )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val launchGeneration =
        AtomicLong(0L)

    private val extrasGeneration =
        AtomicLong(0L)

    /**
     * VRShell MainActivity를 직접 시작하지 않습니다.
     *
     * ShellControlBroadcastReceiver가 NavigationRouter를 통해
     * launch transition과 target immersive Activity를 연결하도록 합니다.
     */
    fun buildIntent(
        context: Context,
        packageName: String
    ): Intent {
        val normalizedPackage =
            packageName.trim()

        require(
            normalizedPackage.isNotBlank()
        ) {
            "packageName is blank"
        }

        return Intent(
            ACTION_VRSHELL_LAUNCH
        ).apply {
            addCategory(
                Intent.CATEGORY_LAUNCHER
            )

            data =
                Uri.parse(
                    "apk://$normalizedPackage"
                )

            setPackage(
                VRSHELL_PACKAGE
            )

            component =
                ComponentName(
                    VRSHELL_PACKAGE,
                    VRSHELL_CONTROL_RECEIVER
                )

            addFlags(
                Intent.FLAG_RECEIVER_FOREGROUND
            )

            addFlags(
                Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            )

            /*
             * VRShell 내부 IntentParser/ShellApplication이 사용하는
             * 실제 호출 source package 키입니다.
             */
            putExtra(
                EXTRA_INTENT_SOURCE_PACKAGE,
                context.packageName
            )
        }
    }

    /**
     * VRShell launch broadcast를 Main Looper에서 정확히 한 번 전송합니다.
     *
     * broadcast가 dispatch된 직후 Agent MainActivity task를 제거합니다.
     * 이 순서는 Quest용 launcher의:
     *
     * launcherActivity.startActivity(vrShellIntent)
     * launcherActivity.finishAffinity()
     *
     * 동작과 동일한 목적을 갖습니다.
     */
    fun launch(
        context: Context,
        packageName: String,
        extrasJson: String? = null,
        reason: String = ""
    ): Boolean {
        val normalizedPackage =
            packageName.trim()

        if (normalizedPackage.isBlank()) {
            return false
        }

        val launchToken =
            launchGeneration
                .incrementAndGet()

        val extrasToken =
            extrasGeneration
                .incrementAndGet()

        val appContext =
            context.applicationContext

        val launchIntent =
            try {
                buildIntent(
                    context = appContext,
                    packageName = normalizedPackage
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "VRShell broadcast intent creation failed. " +
                            "package=$normalizedPackage reason=$reason",
                    e
                )

                return false
            }

        val completed =
            AtomicBoolean(false)

        val dispatched =
            AtomicBoolean(false)

        val latch =
            CountDownLatch(1)

        val action =
            Runnable {
                try {
                    if (
                        launchToken !=
                        launchGeneration.get()
                    ) {
                        Log.i(
                            TAG,
                            "Stale VRShell launch suppressed. " +
                                    "package=$normalizedPackage " +
                                    "token=$launchToken " +
                                    "current=${launchGeneration.get()} " +
                                    "reason=$reason"
                        )

                        return@Runnable
                    }

                    /*
                     * 과거 종료 버전의 Shield가 남아 있을 때만 제거합니다.
                     * 새 Shield Activity는 생성하지 않습니다.
                     */
                    IdleShieldActivity
                        .finishCurrentForExternalLaunchNow()

                    appContext.sendBroadcast(
                        launchIntent
                    )

                    dispatched.set(
                        true
                    )

                    /*
                     * R9에서 남겨둔 Agent 2D panel task를 제거합니다.
                     * 다음 실행은 MainActivity source가 없어도 이 broadcast 경로로 동작합니다.
                     */
                    MainActivity.finishCurrent()

                    Log.i(
                        TAG,
                        "Quest VRShell launch broadcast dispatched. " +
                                "route=VRSHELL_CONTROL_BROADCAST " +
                                "package=$normalizedPackage " +
                                "action=${launchIntent.action} " +
                                "data=${launchIntent.data} " +
                                "component=${launchIntent.component} " +
                                "sourcePackage=${appContext.packageName} " +
                                "reason=$reason"
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Quest VRShell launch broadcast failed. " +
                                "package=$normalizedPackage " +
                                "reason=$reason",
                        e
                    )
                } finally {
                    completed.set(
                        true
                    )

                    latch.countDown()
                }
            }

        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            action.run()
        } else {
            mainHandler.post(
                action
            )
        }

        val finished =
            try {
                latch.await(
                    MAIN_DISPATCH_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()

                false
            }

        if (!finished) {
            launchGeneration.compareAndSet(
                launchToken,
                launchToken + 1L
            )

            extrasGeneration.compareAndSet(
                extrasToken,
                extrasToken + 1L
            )

            Log.e(
                TAG,
                "Quest VRShell broadcast main-thread timeout. " +
                        "package=$normalizedPackage " +
                        "timeoutMs=$MAIN_DISPATCH_TIMEOUT_MS " +
                        "reason=$reason"
            )

            return false
        }

        if (
            completed.get() &&
            dispatched.get()
        ) {
            scheduleCompatibilityExtras(
                context = appContext,
                packageName = normalizedPackage,
                extrasJson = extrasJson,
                reason = reason,
                expectedToken = extrasToken
            )

            return true
        }

        return false
    }

    /**
     * STOP/STOP_ALL이 들어오면 아직 실행되지 않은 VRShell launch 및
     * stage/lesson 호환 방송을 취소합니다.
     */
    fun cancelPending(
        reason: String
    ) {
        val launchToken =
            launchGeneration
                .incrementAndGet()

        val extrasToken =
            extrasGeneration
                .incrementAndGet()

        Log.i(
            TAG,
            "Pending VRShell work cancelled. " +
                    "reason=$reason " +
                    "launchToken=$launchToken " +
                    "extrasToken=$extrasToken"
        )
    }

    private fun scheduleCompatibilityExtras(
        context: Context,
        packageName: String,
        extrasJson: String?,
        reason: String,
        expectedToken: Long
    ) {
        if (extrasJson.isNullOrBlank()) {
            return
        }

        val args =
            try {
                JSONObject(
                    extrasJson
                )
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Launch extras parse failed. " +
                            "package=$packageName " +
                            "extras=$extrasJson",
                    e
                )

                return
            }

        if (args.length() == 0) {
            return
        }

        val command =
            args.optString(
                "command",
                ""
            ).trim().ifBlank {
                when {
                    args.has("stage") ->
                        "goStage"

                    else ->
                        "launchExtras"
                }
            }

        val requestId =
            args.optString(
                "requestId",
                ""
            ).trim().ifBlank {
                "launch-" +
                        packageName +
                        "-" +
                        SystemClock.elapsedRealtime()
            }

        EXTRA_DELAYS_MS
            .forEachIndexed {
                    index,
                    delayMs ->

                mainHandler.postDelayed(
                    {
                        if (
                            expectedToken !=
                            extrasGeneration.get()
                        ) {
                            return@postDelayed
                        }

                        sendCompatibilityExtras(
                            context = context,
                            packageName = packageName,
                            command = command,
                            requestId = requestId,
                            args = args,
                            attempt = index + 1,
                            totalAttempts =
                                EXTRA_DELAYS_MS.size,
                            reason = reason
                        )
                    },
                    delayMs
                )
            }
    }

    private fun sendCompatibilityExtras(
        context: Context,
        packageName: String,
        command: String,
        requestId: String,
        args: JSONObject,
        attempt: Int,
        totalAttempts: Int,
        reason: String
    ) {
        try {
            val envelope =
                JSONObject()
                    .put(
                        "requestId",
                        requestId
                    )
                    .put(
                        "command",
                        command
                    )
                    .put(
                        "args",
                        JSONObject(
                            args.toString()
                        )
                    )
                    .put(
                        "source",
                        "vrshell_launch_compat"
                    )
                    .put(
                        "timestamp",
                        System.currentTimeMillis()
                    )
                    .put(
                        "attempt",
                        attempt
                    )
                    .put(
                        "retryCount",
                        totalAttempts
                    )

            val intent =
                Intent(
                    ACTION_STORYWING_COMMAND
                ).apply {
                    setPackage(
                        packageName
                    )

                    addFlags(
                        Intent.FLAG_RECEIVER_FOREGROUND
                    )

                    addFlags(
                        Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    )

                    putExtra(
                        "command",
                        command
                    )

                    putExtra(
                        "action",
                        command
                    )

                    putExtra(
                        "requestId",
                        requestId
                    )

                    putExtra(
                        "attempt",
                        attempt
                    )

                    putExtra(
                        "retryCount",
                        totalAttempts
                    )

                    /*
                     * 기존 MultiQuestCommandBridge.aar가 Unity에 전달하는 핵심 값입니다.
                     */
                    putExtra(
                        "payload",
                        envelope.toString()
                    )

                    putExtra(
                        "rawPayload",
                        args.toString()
                    )

                    putJsonExtras(
                        this,
                        args
                    )
                }

            context.sendBroadcast(
                intent
            )

            Log.i(
                TAG,
                "Launch extras compatibility broadcast sent. " +
                        "package=$packageName " +
                        "command=$command " +
                        "attempt=$attempt/$totalAttempts " +
                        "requestId=$requestId " +
                        "reason=$reason"
            )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Launch extras compatibility broadcast failed. " +
                        "package=$packageName " +
                        "attempt=$attempt/$totalAttempts",
                e
            )
        }
    }

    private fun putJsonExtras(
        intent: Intent,
        json: JSONObject
    ) {
        val keys =
            json.keys()

        while (keys.hasNext()) {
            val key =
                keys.next()

            val value =
                json.opt(
                    key
                )

            when (value) {
                is Int ->
                    intent.putExtra(
                        key,
                        value
                    )

                is Long ->
                    intent.putExtra(
                        key,
                        value
                    )

                is Double -> {
                    val asLong =
                        value.toLong()

                    if (
                        value ==
                        asLong.toDouble()
                    ) {
                        intent.putExtra(
                            key,
                            asLong
                        )
                    } else {
                        intent.putExtra(
                            key,
                            value
                        )
                    }
                }

                is Boolean ->
                    intent.putExtra(
                        key,
                        value
                    )

                is String ->
                    intent.putExtra(
                        key,
                        value
                    )

                JSONObject.NULL ->
                    Unit

                else ->
                    intent.putExtra(
                        key,
                        value?.toString()
                    )
            }
        }
    }
}