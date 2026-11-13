package tdg.oculuswirelessadb.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

class StoryWingAppStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MQ-AppState"

        const val ACTION_APP_STATE = "com.storywing.multiquest.APP_STATE"
        const val ACTION_COMMAND_ACK = "com.storywing.multiquest.COMMAND_ACK"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_STATE = "state"

        const val STATE_OPEN = "open"
        const val STATE_ACTIVE = "active"
        const val STATE_PAUSED = "paused"
        const val STATE_CLOSED = "closed"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_COMMAND_ACK) {
            handleCommandAck(intent)
            return
        }

        if (intent.action != ACTION_APP_STATE) return

        val packageName = intent
            .getStringExtra(EXTRA_PACKAGE_NAME)
            ?.trim()
            .orEmpty()

        val state = intent
            .getStringExtra(EXTRA_STATE)
            ?.trim()
            ?.lowercase()
            .orEmpty()

        val reason = intent
            .getStringExtra("reason")
            ?.trim()
            .orEmpty()

        Log.i(
            TAG,
            "APP_STATE received. " +
                    "agentApplicationId=${context.packageName} " +
                    "sourcePackage=$packageName state=$state reason=$reason"
        )

        if (packageName.isBlank()) {
            Log.w(TAG, "APP_STATE ignored: packageName blank")
            return
        }

        if (!packageName.startsWith("com.StoryWing.", ignoreCase = true)) {
            Log.w(TAG, "APP_STATE ignored: unexpected package=$packageName")
            return
        }

        when (state) {
            STATE_OPEN -> {
                LastLaunchedAppStore.markOpen(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_OPEN"
                )
            }

            STATE_ACTIVE -> {
                LastLaunchedAppStore.markOpen(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_ACTIVE"
                )

                AgentOperationStore.completeLatestLaunchForPackage(
                    packageName = packageName,
                    appState = state
                )

                /*
                 * ACTIVE는 전면 진입 확인일 뿐 외부 앱 세션 종료가 아닙니다.
                 * 세션을 지우거나 MediaProjection 자동 복구를 재개하지 않습니다.
                 */
                AppLaunchGuard.markForegroundReady(packageName)
                LaunchFocusStabilizer.onAppActive(
                    context = context.applicationContext,
                    packageName = packageName
                )

                Log.i(
                    TAG,
                    "StoryWing ACTIVE confirmed. " +
                            "external session retained; capture UI remains blocked. " +
                            "package=$packageName"
                )
            }

            STATE_PAUSED -> {
                LastLaunchedAppStore.markOpen(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_PAUSED"
                )

                LaunchFocusStabilizer.onAppPausedDuringLaunch(
                    context = context.applicationContext,
                    packageName = packageName
                )
            }

            STATE_CLOSED -> {
                if (AppLaunchGuard.isFirstFrameProtectionActiveFor(packageName)) {
                    /*
                     * forceRestart/Scene 전환 직후 이전 Activity가 늦게 보낸 CLOSED를
                     * 새 외부 앱 세션 종료로 오판하지 않습니다.
                     */
                    Log.i(
                        TAG,
                        "Stale CLOSED ignored during first-frame protection. " +
                                "package=$packageName remainingMs=${AppLaunchGuard.remainingMs()}"
                    )
                    return
                }

                LastLaunchedAppStore.markClosed(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_CLOSED"
                )

                AppLaunchGuard.clearFor(packageName)
                LaunchFocusStabilizer.cancelForPackage(
                    packageName = packageName,
                    reason = "APP_STATE_CLOSED"
                )

                AgentOperationStore.failLatestLaunchForPackage(
                    packageName = packageName,
                    message = "APP_STATE_CLOSED_BEFORE_ACTIVE",
                    retryable = true
                )

                /*
                 * WPF STOP/STOP_ALL이 걸어 둔 무기한 pause는 유지합니다.
                 * 사용자가 앱 내부에서 정상 종료한 경우에만 자동 복구를 다시 허용합니다.
                 */
                val pauseReason = AutoCapturePauseStore
                    .reason(context.applicationContext)
                    .uppercase()

                val stopPauseActive =
                    pauseReason.contains("APP_STOP") ||
                            pauseReason.contains("STOP_ALL") ||
                            pauseReason.contains("MANUAL_STOP")

                if (!stopPauseActive) {
                    AutoCapturePauseStore.resume(context.applicationContext)
                    AgentService.start(context.applicationContext)
                }

                Log.i(
                    TAG,
                    "StoryWing CLOSED handled. " +
                            "package=$packageName stopPauseActive=$stopPauseActive"
                )
            }

            else -> {
                Log.w(
                    TAG,
                    "APP_STATE unknown. package=$packageName state=$state"
                )
            }
        }
    }

    private fun handleCommandAck(intent: Intent) {
        val operationId = intent
            .getStringExtra("operationId")
            ?.trim()
            .orEmpty()

        if (operationId.isBlank()) {
            Log.w(
                TAG,
                "COMMAND_ACK ignored: operationId blank. " +
                        "sourcePackage=${intent.getStringExtra(EXTRA_PACKAGE_NAME)}"
            )
            return
        }

        if (!AgentOperationStore.isPending(operationId)) {
            Log.w(
                TAG,
                "COMMAND_ACK ignored: operation not pending. " +
                        "operationId=$operationId " +
                        "sourcePackage=${intent.getStringExtra(EXTRA_PACKAGE_NAME)}"
            )
            return
        }

        val packageName = intent
            .getStringExtra(EXTRA_PACKAGE_NAME)
            ?.trim()
            .orEmpty()

        val command = intent
            .getStringExtra("command")
            ?.trim()
            .orEmpty()

        val requestId = intent
            .getStringExtra("requestId")
            ?.trim()
            .orEmpty()

        val status = intent
            .getStringExtra("status")
            ?.trim()
            ?.lowercase()
            .orEmpty()

        val message = intent
            .getStringExtra("message")
            ?.trim()
            .orEmpty()

        val details = JSONObject()
            .put("packageName", packageName)
            .put("command", command)
            .put("requestId", requestId)
            .put("ackStatus", status)
            .put("ackMessage", message)

        when (status) {
            "received",
            "stored",
            "applied",
            "completed",
            "ok" -> {
                AgentOperationStore.complete(
                    operationId = operationId,
                    message = "COMMAND_ACK_${status.ifBlank { "received" }}",
                    details = details
                )
            }

            "failed",
            "error",
            "rejected" -> {
                AgentOperationStore.fail(
                    operationId = operationId,
                    message = message.ifBlank { "app_command_rejected" },
                    retryable = true,
                    details = details
                )
            }

            else -> {
                AgentOperationStore.complete(
                    operationId = operationId,
                    message = "COMMAND_ACK_RECEIVED",
                    details = details
                )
            }
        }

        Log.i(
            TAG,
            "COMMAND_ACK. operationId=$operationId " +
                    "package=$packageName command=$command status=$status"
        )
    }
}